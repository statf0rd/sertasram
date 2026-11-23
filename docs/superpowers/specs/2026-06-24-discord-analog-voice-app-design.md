# Дизайн: Кроссплатформенное голосовое/видео приложение (аналог Discord)

**Дата:** 2026-06-24
**Статус:** утверждён (дизайн), готов к написанию плана реализации
**Кодовое имя проекта:** `sertas`

---

## 1. Обзор и цели

Десктоп-приложение в духе Discord, сфокусированное на **качественной realtime-связи в малой группе**: голос, камера и — ключевое — **демонстрация экрана с высоким FPS и чистым системным звуком демонстрации**, плюс чистый звук участников.

Главный вызов и приоритет проекта — именно демонстрация: высокий FPS видео экрана и **передача системного звука** того, что демонстрируется (музыка, игра, видео), без деградации обработкой.

### Критерии успеха
- Звонок до 4 участников: голос + камера + демонстрация одновременно, без заметных заиканий.
- Демонстрация экрана идёт с высоким FPS (цель: 1080p при 30–60 FPS, с лимитами под полосу).
- Системный звук демонстрации слышен участникам **чисто** (стерео, высокий битрейт), не испорчен шумодавом/AGC.
- Шумоподавление и эхоподавление микрофона включаются/выключаются пользователем; по умолчанию — режим «чистого звука» доступен в один клик.
- Работает на macOS и Windows (Linux — следующий этап).

---

## 2. Объём (Scope) и не-цели

### Делаем
- Вход в комнату по коду + ник (анонимно, без регистрации).
- P2P-меш до 4 участников.
- Голос: микрофон, mute, deafen (заглушить весь входящий), индивидуальная громкость каждого участника, выбор устройств ввода/вывода.
- Переключаемые шумоподавление + эхоподавление + AGC (приоритет — чистый звук).
- Камера (включить/выключить, выбор камеры).
- Демонстрация экрана с системным звуком; выбор экрана/окна; пресеты качества (FPS/разрешение).
- Сетка участников с индикацией состояния (говорит/замьючен/демонстрирует).

### НЕ делаем (явно вне объёма)
- Роли и права, модерация.
- Текстовый чат, история сообщений.
- Серверная иерархия (серверы/категории/каналы Discord).
- Аккаунты, регистрация, профили, друзья.
- Постоянное хранилище / база данных.
- Мобильные платформы (iOS/Android), Windows-on-ARM.

---

## 3. Технологический стек

| Слой | Технология | Примечание |
|------|-----------|-----------|
| UI | JavaFX 21 | На JDK 21 LTS |
| Медиа-движок | `dev.onvoid.webrtc:webrtc-java:0.14.0` | Apache-2.0; нативный libwebrtc BSD-3 |
| Сигналинг-сервер | Java + WebSocket (Javalin или Java-WebSocket) | Без БД, состояние в памяти |
| Захват системного звука (macOS) | ScreenCaptureKit (Swift) → `.dylib` через JNI | Одно разрешение Screen Recording покрывает видео+звук |
| Захват системного звука (Windows) | WASAPI loopback (C++) → `.dll` через JNI | `AUDCLNT_STREAMFLAGS_LOOPBACK` |
| Конвертация цвета/семплов | libyuv (внутри webrtc-java) | Без FFmpeg/JavaCV в основном тракте |
| Сборка | Gradle (multi-module) | Нативные классификаторы через `os-detector` |
| Дистрибуция | jpackage | Инсталляторы per-OS |

**Почему `webrtc-java`, а не «с нуля» / LiveKit:** realtime-сложность (AEC, jitter-buffer/NetEQ, FEC/NACK, congestion control/GCC) — это libwebrtc. Подтверждено по исходникам: библиотека даёт `RTCPeerConnection` 1:1 к W3C, кастомные источники аудио/видео, встроенный захват экрана, полный APM. LiveKit/Janus — серверные SFU, избыточны для меша ≤4 и хуже стыкуются с Java-клиентом.

**Почему без FFmpeg/JavaCV в ядре:** `webrtc-java` + libyuv покрывают захват, кодеки, декод и конвертацию цвета. Это проще и сохраняет пермиссивную лицензию (FFmpeg с GPL-компонентами «заразил» бы весь бинарь).

---

## 4. Архитектура модулей

Gradle multi-module. Каждый модуль — одна зона ответственности и явный интерфейс; `core-call` не знает про JavaFX, `ui` не знает про libwebrtc, `native-capture` отдаёт сырые PCM/кадры.

```
sertas/
├── signaling-server/      # WebSocket-сервер: комнаты, relay SDP+ICE, presence. Медиа НЕ трогает.
├── app-client/            # JavaFX-десктоп-клиент
│   ├── core-call/         #   PeerConnectionFactory (один общий), по 1 RTCPeerConnection на пира,
│   │                      #   логика меша, управление треками/трансиверами
│   ├── media-capture/     #   абстракции источников: микрофон, камера, экран(видео), системный звук
│   ├── audio-pipeline/    #   APM-конфиг (вкл/выкл NS/AEC/AGC), дизайн «2 трека», Opus SDP-munging
│   ├── render/            #   VideoTrackSink → I420→BGRA → PixelBuffer → плитки JavaFX
│   ├── signaling-client/  #   WebSocket-клиент, JSON-протокол, perfect negotiation
│   └── ui/                #   экраны: вход в комнату, сетка звонка, контролы, настройки устройств
└── native-capture/        # JNI-мост (Java) + нативные хелперы:
    ├── macos/             #   ScreenCaptureKit (Swift): экран-видео + системный звук
    └── windows/           #   WASAPI loopback (C++): системный звук
```

---

## 5. Медиа-тракт

### 5.1 Исходящий (от меня к каждому из ≤3 пиров)

Один общий `PeerConnectionFactory`. На каждого удалённого пира — отдельный `RTCPeerConnection`, в который добавляются нужные треки через `addTrack(track, streamIds)`.

| Трек | Источник | Кодек/параметры |
|------|----------|-----------------|
| `mic` | `AudioSource` (микрофон) → **глобальный APM** | Opus VOIP, моно, ~32–40 кбит, `useinbandfec=1`, `usedtx=1` |
| `camera` | camera `VideoSource` | VP8 |
| `screen` | `VideoDesktopSource` (встроенный) | VP8, лимиты `setMaxFrameSize`, `maxFramerate`, `maxBitrate` |
| `screen-audio` | нативный хелпер → `CustomAudioSource` | Opus **стерео**, **в обход APM** (см. §6) |

**Звук демонстрации (ключевой механизм, подтверждён по исходникам):**
`CustomAudioSource.pushAudio(byte[] pcm, int bitsPerSample, int sampleRate, int channels, int frameCount)`.
Формат: 16-бит signed interleaved, 48000 Гц, 2 канала, **480 фреймов на 10 мс** (длина буфера `480*2*2 = 1920` байт). Пушим равномерно каждые 10 мс; при цифровой тишине источника **досылаем тишину**, чтобы держать ритм и не плыла A/V-синхронизация. PCM, поданный так, идёт прямо в энкодер **мимо микрофонного APM** — поэтому никакой шумодав не трогает музыку.

**Кадры экрана (если понадобится кастомный путь вместо `VideoDesktopSource`):**
`NativeI420Buffer.allocate(w,h)` → `VideoBufferConverter.convertToI420(src, i420, FourCC.BGRA)` → `new VideoFrame(buffer, System.nanoTime())` → `CustomVideoSource.pushFrame(frame)` → `frame.dispose()`. Конвертацию в I420 делаем в нативном коде (вне JVM-кучи), direct ByteBuffer даёт zero-copy путь.

### 5.2 Входящий (рендер)

`videoTrack.addSink(VideoTrackSink)`. Колбэк `onVideoFrame` приходит на **нативном WebRTC-потоке** (не FX!). В нём:
1. `VideoBufferConverter.convertFromI420(frame.getBuffer(), directByteBuffer, FourCC.BGRA)` в заранее выделенный **direct ByteBuffer** (`w*h*4`, переиспользуется на плитку; реаллок только при смене разрешения).
2. Маршалим **только** `pixelBuffer.updateBuffer(b -> null)` на FX-поток, коалесим через `AnimationTimer` (atomic «последний кадр», стейл-кадры дропаем) — рендерим не чаще refresh-rate.
3. Колбэк не блокируем, `VideoFrame` за пределы колбэка не держим.

Отображение: `javafx.scene.image.PixelBuffer<ByteBuffer>` с `PixelFormat.getByteBgraPreInstance()` → `WritableImage` → `ImageView` на плитку. Аудио удалённых пиров микшируется и проигрывается `AudioDeviceModule` автоматически.

---

## 6. Дизайн чистого звука («2 трека»)

APM в libwebrtc **глобальный** (один на capture-путь микрофона), не per-track. Поэтому:

- **Микрофон** → один `AudioProcessing` (APM), конфиг `AudioProcessingConfig` с независимыми флагами:
  `echoCanceller.enabled`, `noiseSuppression.enabled` (+ `Level` LOW/MODERATE/HIGH/VERY_HIGH), `gainController.enabled` / `gainControllerDigital`, `highPassFilter.enabled`. Передаётся в `new PeerConnectionFactory(adm, apm)`.
  - **Три кнопки-тумблера** (NS / AEC / AGC) мутируют конфиг и зовут `apm.applyConfig(cfg)` заново. Переключение во время звонка даёт <1с артефакт (библиотека предупреждает) — приемлемо для нажатия кнопки.
  - **Дефолты:** NS/AEC/AGC **вкл** (у большинства открытые колонки + шум комнаты). «Чистый сырой звук» — выключить всё одним пресетом.
  - AEC получает render-reference автоматически, т.к. воспроизведение идёт через `AudioDeviceModule` библиотеки (ручной `processReverseStream` не нужен).
- **Звук демонстрации** → отдельный трек из `CustomAudioSource`, **полностью мимо APM**. Для верности можно подать через `HeadlessAudioDeviceModule`, если потребуется чистый pushed-PCM путь.

**Opus «музыкальный режим»** для трека `screen-audio` — через SDP-munging локального описания (нет чистого API в 2026):
`stereo=1; sprop-stereo=1; maxaveragebitrate=192000; useinbandfec=1; usedtx=0; cbr=0` + у сендера `encodings[0].maxBitrate ≈ 256000` (`RtpSender.setParameters`). **Оба** нужны, иначе энкодер сам зажимает битрейт; `usedtx=1`/низкий битрейт принудительно уводят Opus в SILK и портят музыку.

---

## 7. Захват per-OS (модуль `native-capture`)

Сам `webrtc-java` PCM принимает; **добыть** системный звук — главная нативная работа, изолирована в `native-capture`.

### macOS (цель: 13+)
- **На старте — нативный хелпер только для системного звука.** Экран-видео идёт через встроенный `VideoDesktopSource` (см. §5.1, §16); апгрейд на нативный SCStream-видео — отдельным шагом, если FPS встроенного захвата недостаточен.
- **Системный звук** через **ScreenCaptureKit** (`SCStream` + `SCStreamConfiguration`, `capturesAudio=true`, `excludesCurrentProcessAudio=true` — иначе фидбэк). Если позже переходим на нативный захват видео — тот же `SCStream` отдаёт и видео (`minimumFrameInterval` до refresh дисплея, `queueDepth` 5–6, `pixelFormat` `420v`, zero-copy `IOSurface`), что объединяет оба потока под одно разрешение.
- Звук приходит Float32 planar, 48k стерео → конвертируем в **S16 interleaved** нативно → JNI → `CustomAudioSource.pushAudio`.
- Разрешение Screen Recording (TCC) нужно для демонстрации в любом случае — поэтому ScreenCaptureKit для звука «бесплатен» по разрешениям.
- Альтернатива на 14.4+: Core Audio process taps (легче по разрешению, но звук-only и хуже документирован) — как опция, не основной путь.
- Референсы: `insidegui/AudioCap`, `makeusabrew/audiotee`.

### Windows (цель: Win10 2004+ / Win11)
- **Системный звук** через **WASAPI loopback**: `IAudioClient` с `AUDCLNT_STREAMFLAGS_LOOPBACK` → `IAudioCaptureClient::GetBuffer`. Per-app loopback (`ActivateAudioInterfaceAsync` + `VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK`, build 20348+) — позже; там надо **хардкодить `WAVEFORMATEX`** (`GetMixFormat` возвращает `E_NOTIMPL`).
- Loopback **молчит при цифровой тишине** → досылаем тишину для ритма 10 мс.
- **Экран-видео** на старте — встроенный `VideoDesktopSource`; апгрейд на нативный Windows.Graphics.Capture (WGC, GPU-текстуры, per-window) — если FPS недостаточен.

### Мост JNI
Нативный хелпер делает захват и конвертацию (BGRA→I420, Float32→S16) **в нативном коде**, отдаёт в Java готовые буферы → Java пушит в кастомные источники. Сидекар/`.dylib`/`.dll` через JNI — самый надёжный путь (Java FFM/Panama против ScreenCaptureKit/CoreAudio — высокий риск, не основной путь).

---

## 8. Рендеринг видео в JavaFX

Путь высокого FPS (бенчмарки: `PixelBuffer` быстрее `Canvas`/`SwingFXUtils` в разы):
- Один direct ByteBuffer на плитку, обёрнут в `PixelBuffer<ByteBuffer>` (`BYTE_BGRA_PRE`) → `WritableImage` → `ImageView`.
- Конвертация I420→BGRA вне FX-потока; на FX-поток только `updateBuffer`.
- `AnimationTimer` + atomic «последний кадр» против затопления FX-очереди.
- Реаллок буфера только при смене разрешения трека.
- Внимание: порядок каналов BGRA ↔ `FourCC.BGRA` (иначе R/B меняются местами) — проверить на каждой ОС.
- Референс: `lectureStudio` (тот же автор, что у webrtc-java, Java/JavaFX + webrtc-java), `vlcj-javafx` (техника PixelBuffer).

---

## 9. Сигналинг-протокол (WebSocket JSON)

Сервер хранит только комнаты и список пиров в памяти; медиа не проходит через сервер.

| Сообщение | Направление | Полезная нагрузка |
|-----------|-------------|-------------------|
| `join` | client→server | `{room, name}` |
| `room-state` | server→client | `{selfId, peers:[{id,name}]}` |
| `peer-joined` | server→client | `{id, name}` |
| `peer-left` | server→client | `{id}` |
| `offer` / `answer` | relay (адресно) | `{from, to, sdp}` |
| `ice-candidate` | relay (адресно) | `{from, to, candidate}` |
| `track-meta` | relay/broadcast | `{from, kind:"mic\|camera\|screen\|screen-audio", state}` |

Меш-негоциация — **perfect negotiation** (роли polite/impolite, чтобы гасить glare при одновременных офферах). Новый участник: существующие пиры инициируют offer к нему.

---

## 10. Управление полосой и качеством

- Меш ×3 на отправителе — главный лимит. Жёстко лимитируем `screen` трек: `VideoDesktopSource.setMaxFrameSize`, `RTCRtpEncodingParameters.maxFramerate` и `maxBitrate`.
- **Пресеты демонстрации:** «Плавность» (выше FPS, ниже разрешение/битрейт) ↔ «Чёткость» (ниже FPS, выше разрешение). Применяются через `RtpSender.setParameters`.
- Полагаемся на congestion control libwebrtc; адаптивно снижаем битрейт музыки при нехватке аплинка.
- Кодек по умолчанию **VP8** (royalty-free, широкая совместимость); H.264 — опция (OpenH264-условия + проверка интеропа per-OS).

---

## 11. Обработка ошибок и разрешения

- **Разрешения:** экран онбординга; детект отказа Screen Recording (macOS) / доступа к микрофону; инструкции и deep-link в системные настройки.
- **Дисконнекты пиров:** ICE-restart (`restartIce`), таймауты, корректное удаление плитки при `peer-left`.
- **Краши/утечки нативного слоя:** строгий `dispose()` в `finally` для `PeerConnection`/треков/источников/`VideoFrame`; не держать буферы за пределами колбэка.
- **Аудио-формат:** юнит-тесты на конвертеры (Float32 planar → S16 interleaved), правильный layout каналов, инжект тишины для ритма.

---

## 12. Тестирование

- **Юнит:** протокол сигналинга, room-state машина, логика SDP-munging, конвертеры аудио-форматов, лимиты битрейта.
- **Интеграция:** два клиента на localhost против локального сигналинг-сервера — loopback-звонок (голос, затем видео).
- **Ручное/soak:** замер FPS демонстрации; leak-тест меша (часовой звонок, мониторинг нативной памяти); A/B качества звука (NS вкл/выкл; музыка через `screen-audio` трек).

---

## 13. Упаковка и дистрибуция

- jpackage per-OS; бандлить правильный классификатор `webrtc-java-native` + свой нативный хелпер (на macOS — universal/dual-arch `.dylib`).
- macOS: нотаризация, entitlements (`NSAudioCaptureUsageDescription`, доступ к экрану/микрофону/камере).
- Windows: подпись, манифест.
- Размер бинаря большой (нативные библиотеки) — учесть.

---

## 14. Главные риски

1. **Захват системного звука** — главная нативная работа, особенно macOS-специфика; изолирована в `native-capture`, по хелперу на ОС, онбординг разрешений.
2. **Pre-1.0 библиотека (один мейнтейнер):** история утечек/крашей в нативном слое. Пин версии, строгий `dispose()`, soak-тесты.
3. **Нагрузка меша ×3** на аплинк/CPU при демо 1080p: жёсткие лимиты, пресеты, адаптация. (SFU — путь апгрейда, если число участников вырастет.)
4. **SDP-munging хрупок:** редактируем точную fmtp-строку Opus; нужен и `maxBitrate` сендера, иначе энкодер зажимает.
5. **Конвертация форматов аудио** — тихие баги (каналы/layout); WASAPI молчит в тишине (досылать тишину).
6. **API-дрейф pre-1.0:** кастомные источники появились в 0.14.0 (авг 2025); сверять сигнатуры с запиненной версией; in-repo JavaFX-демо удалено — рендер-glue писать самим по jrtc.dev + lectureStudio.

---

## 15. Этапы реализации (macOS + Windows)

- **Фаза 0 — Сквозной тракт:** скелет Gradle, сигналинг-сервер, звонок 1-на-1 только голосом (микрофон, встроенный) между двумя клиентами на localhost.
- **Фаза 1 — Меш и видео:** меш до 4, камера, сетка-рендер (PixelBuffer), mute/deafen/громкость по участнику, выбор устройств.
- **Фаза 2 — Демонстрация (видео):** `VideoDesktopSource`, выбор экрана/окна, пресеты качества, лимиты битрейта.
- **Фаза 3 — Системный звук демо (ядро ценности):** нативные хелперы ScreenCaptureKit (macOS) + WASAPI loopback (Windows) → `CustomAudioSource` + Opus-стерео + SDP-munging.
- **Фаза 4 — Полировка звука и релиз:** тумблеры NS/AEC/AGC, дефолты «чистого звука», soak/leak-тесты, jpackage-инсталляторы, онбординг разрешений.

---

## 16. Принятые решения по умолчанию

- Кодек: **VP8**.
- Системный звук: **весь системный** (per-app — позже, только Windows).
- Экран: на старте **встроенный `VideoDesktopSource`**, апгрейд на нативный при нехватке FPS.
- Целевые версии ОС: **macOS 13+**, **Windows 10 2004+**.
- Платформы MVP: **macOS + Windows**; Linux (xdg-desktop-portal + PipeWire) — следующий этап.

---

## 17. Ключевые ссылки

- webrtc-java: https://github.com/devopvoid/webrtc-java · доки https://jrtc.dev
- Кастомные источники: https://jrtc.dev/guide/audio/custom-audio-source · https://jrtc.dev/guide/video/custom-video-source
- Захват экрана: https://jrtc.dev/guide/video/desktop-capture
- macOS системный звук: https://developer.apple.com/documentation/screencapturekit · `insidegui/AudioCap` · `makeusabrew/audiotee`
- Windows loopback: https://learn.microsoft.com/windows/win32/coreaudio/loopback-recording
- Рендер JavaFX: https://foojay.io/today/high-performance-rendering-in-javafx/ · `lectureStudio` · `vlcj-javafx`

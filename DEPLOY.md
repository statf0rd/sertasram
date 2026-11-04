# Деплой сигналинг-сервера

Сигналинг-сервер — чистая Java (Javalin/Jetty), нативного кода нет, поэтому
кроссплатформенно собирается на любой машине и запускается на любом Linux-VPS с
Java 21. Ниже — как поднять его как постоянный systemd-сервис.

Имена `sertas-signaling`, `/opt/sertas-signaling` и `SERTAS_*` остались от старого
названия проекта (sertas, теперь sertasram) — это внутренние имена, менять их не нужно.

Подставьте свои значения:

```bash
VPS=your-vps          # ssh-хост (alias из ~/.ssh/config или user@ip)
PORT=8080             # порт сигналинга
```

## 1. Java 21 на VPS (если нет)

```bash
ssh "$VPS" '
  mkdir -p /opt/java21
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse" -o /tmp/jre21.tar.gz
  tar -xzf /tmp/jre21.tar.gz -C /opt/java21 --strip-components=1
  /opt/java21/bin/java -version
'
```

## 2. Собрать и залить дистрибутив

```bash
./gradlew :signaling-server:installDist
tar -czf /tmp/sertas-signaling.tar.gz -C signaling-server/build/install signaling-server
scp /tmp/sertas-signaling.tar.gz "$VPS":/tmp/
ssh "$VPS" '
  rm -rf /opt/sertas-signaling /tmp/signaling-server
  tar -xzf /tmp/sertas-signaling.tar.gz -C /tmp
  mv /tmp/signaling-server /opt/sertas-signaling
'
```

## 3. systemd-сервис (автозапуск + авто-рестарт)

Создать `/etc/systemd/system/sertas-signaling.service` на VPS:

```ini
[Unit]
Description=sertas signaling server
After=network.target

[Service]
Type=simple
Environment=JAVA_HOME=/opt/java21
# Токен доступа: подключения без совпадающего ?token=... отклоняются.
# Сгенерировать: openssl rand -hex 16. Без этой строки сервер пускает всех.
Environment=SERTAS_TOKEN=ВАШ_ТОКЕН
# TURN раздаётся клиентам ОТСЮДА (формат turn:HOST:3478,USER,PASS, см. раздел
# TURN ниже). Без этой строки клиенты получат только STUN и между разными
# NAT не соединятся (у обоих вечное «соединение…»).
Environment=SERTAS_TURN=turn:ВАШ_IP:3478,sertas,ПАРОЛЬ
ExecStart=/opt/sertas-signaling/bin/signaling-server 8080
Restart=always
RestartSec=3
User=root
SyslogIdentifier=sertas-signaling

[Install]
WantedBy=multi-user.target
```

```bash
ssh "$VPS" '
  systemctl daemon-reload
  systemctl enable --now sertas-signaling
  systemctl status sertas-signaling --no-pager
'
```

Откройте порт во внешнем фаерволе провайдера/ufw, если включён.

## Управление сервисом

```bash
systemctl status sertas-signaling      # статус
systemctl restart sertas-signaling     # перезапуск
journalctl -u sertas-signaling -f      # живые логи
```

## Указать сервер в приложении

URL не хранится в коде. Задайте один из источников (см. README). Если на сервере
включён `SERTAS_TOKEN`, добавьте токен в URL как query-параметр:

```
ws://HOST:8080/signal?token=ВАШ_ТОКЕН
```

Источники (в порядке приоритета): `-Dsertas.server=...`, env `SERTAS_SERVER`,
файл `~/.sertas/server`.

## Безопасность

- **Токен доступа (реализовано):** при заданном `SERTAS_TOKEN` сервер отклоняет
  подключения без совпадающего `?token=`. Токен едет в URL, в коде/репозитории
  его нет.
- **Шифрование:** медиа всегда шифруется (DTLS-SRTP). Сам сигналинг по умолчанию
  идёт по **plain WS** (не WSS). Для TLS нужен домен: указать его на IP VPS,
  поставить reverse-proxy (Caddy/nginx) с Let's Encrypt и проксировать на
  `localhost:8080`, в приложении использовать `wss://домен/signal?token=...`.
  (Для bare-IP без домена корректный публичный TLS-сертификат не выдаётся.)
- **NAT:** прямой P2P между разными сетями часто невозможен (симметричный NAT);
  статус участника тогда — «ошибка» (ICE FAILED). Решается TURN-сервером (см.
  ниже).

## TURN-сервер (coturn) для NAT

Ретранслирует медиа, когда прямое соединение невозможно. Лёгкий (~7 МБ).

```bash
ssh "$VPS" '
  DEBIAN_FRONTEND=noninteractive apt-get install -y coturn
  PASS=$(openssl rand -hex 12)
  cat > /etc/turnserver.conf <<CONF
listening-port=3478
fingerprint
lt-cred-mech
user=sertas:${PASS}
realm=sertas
external-ip=ВАШ_IP
min-port=49160
max-port=49200
no-tls
no-dtls
no-cli
simple-log
CONF
  echo TURNSERVER_ENABLED=1 > /etc/default/coturn
  systemctl enable --now coturn
  echo "TURN creds: sertas:${PASS}"
'
```

Открыть во внешнем фаерволе: `3478/udp`, `3478/tcp`, `49160-49200/udp`.

TURN задаётся **на сервере**: строка `Environment=SERTAS_TURN=turn:HOST:3478,USER,PASS`
в юните сигналинга (см. выше) + `systemctl daemon-reload && systemctl restart
sertas-signaling`. Сервер отдаёт его клиентам в `room-state` вместе со STUN, и
клиент использует именно серверный список. Значение в бандле (`-Dsertas.turn`,
env `SERTAS_TURN`, файл `~/.sertas/turn`; вшивается скриптами `package-*.sh`) —
только запасной вариант на случай, когда сервер не прислал ничего; само по себе
оно TURN не включает.

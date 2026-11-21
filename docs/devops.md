# CI/CD и контейнерная инфраструктура сервера

Серверная часть sertasram (сигналинг, TURN, HTTPS-прокси, мониторинг) собирается, проверяется и выкладывается конвейером GitHub Actions. Ручная выкладка через systemd описана в [DEPLOY.md](../DEPLOY.md) и остаётся запасным вариантом.

## Что где лежит

| Путь | Назначение |
| --- | --- |
| `Dockerfile` | образ сигналинг-сервера: сборка на JDK 21, запуск на JRE 21 (Alpine) от непривилегированного пользователя |
| `deploy/docker-compose.yml` | сервер целиком: `signaling`, `caddy` (HTTPS), `turn` (coturn), `prometheus`, `alertmanager`, `grafana` |
| `deploy/docker-compose.local.yml` | надстройка для ноутбука: без домена и TURN, порты на 127.0.0.1 |
| `deploy/docker-compose.shared.yml` | надстройка для сервера с занятыми 80/443: без Caddy, сигналинг на отдельном порту |
| `deploy/scripts/release.sh` | выкладка на машине с Docker: `staging`, `promote`, `rollback`, `status` |
| `deploy/scripts/docker-bench.sh` | проверка хоста по CIS Docker Benchmark |
| `.github/workflows/ci.yml` | тесты, сборка образа и запуск в контейнере, Trivy |
| `.github/workflows/deploy.yml` | образ в ghcr.io, проверочный контейнер на VPS, замена рабочего после подтверждения |

## Четыре режима конвейера

1. **Сборка и запуск в контейнере** (`ci.yml`, задание `image`). Образ собирается, сервер стартует в контейнере с теми же ограничениями, что на сервере, проверяются `/health`, `/metrics` и проба двумя клиентами. Лог запуска сохраняется артефактом `signaling-startup-log`.
2. **Автотесты** (`ci.yml`, задание `test`). Тесты модулей без нативного кода, отчёт JUnit - артефакт `test-report`. Задание назначается обязательной проверкой ветки `main`.
3. **Проверочный контейнер на VPS** (`deploy.yml`, задание `staging`). Новый образ запускается рядом с рабочим на порту 18080, с runner-а снаружи выполняется `DeployedServerProbeTest`.
4. **Замена рабочего контейнера** (`deploy.yml`, задание `production`). Ждёт подтверждения ревьюера окружения `production`. Если новый контейнер не стал healthy за 60 секунд, `release.sh` сам возвращает предыдущий образ.

## Настройка репозитория (один раз)

- Settings - Branches - правило для `main`: Require a pull request, Require status checks: `Автотесты`, `Сборка образа и запуск в контейнере`, `Сканирование уязвимостей (Trivy)`.
- Settings - Environments: `staging` и `production`; у `production` включить Required reviewers.
- Settings - Secrets: `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY` (отдельный ключ только для деплоя), `VPS_KNOWN_HOSTS` (вывод `ssh-keyscan HOST`), `STAGING_TOKEN`.

## Настройка сервера (один раз)

```bash
# Docker и пользователь для деплоя
curl -fsSL https://get.docker.com | sh
useradd -m -G docker deploy
mkdir -p /opt/sertas/deploy && chown -R deploy /opt/sertas

# после первого запуска deploy.yml каталог /opt/sertas/deploy заполнен
cd /opt/sertas/deploy
cp .env.example .env            # заполнить DOMAIN, PUBLIC_IP, токены и пароли
echo 'ТОКЕН_БОТА' > alertmanager/telegram_token && chmod 600 alertmanager/telegram_token
docker compose up -d
```

Открыть в фаерволе: 80, 443 (Caddy), 18080 (проверочный контейнер), 3478 tcp/udp и 49160-49200/udp (TURN).

## Сервер с занятыми портами 80 и 443

Если на машине уже работают другие сервисы (свой прокси, VPN), в `.env` добавляются две строки:

```bash
COMPOSE_FILE=docker-compose.yml:docker-compose.shared.yml
SIGNALING_PORT=8090
```

Надстройка `docker-compose.shared.yml` не запускает Caddy и публикует сигналинг на `SIGNALING_PORT`. Клиент подключается по `ws://HOST:8090/signal?token=...`; чтобы получить `wss`, на существующем прокси добавляется `reverse_proxy 127.0.0.1:8090` и в `.env` ставится `SIGNALING_BIND=127.0.0.1`. В фаерволе открываются `SIGNALING_PORT`, 18080, 3478 tcp/udp и 49160-49200/udp.

## Локальный запуск

```bash
docker build -t sertas-signaling:local .
cd deploy
cp .env.example .env            # SIGNALING_IMAGE=sertas-signaling:local, остальное - любые значения
touch alertmanager/telegram_token
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d
```

Сигналинг - `ws://localhost:8080/signal?token=...`, Prometheus - http://localhost:9090, Grafana - http://localhost:3000 (admin и пароль из `.env`).

## Откат вручную

```bash
cd /opt/sertas/deploy
./scripts/release.sh status      # какой образ запущен и какой был до него
./scripts/release.sh rollback    # вернуть предыдущий
```

Клиент при разрыве соединения переподключается сам, поэтому замена контейнера для участников выглядит как короткое "соединение...".

## Мониторинг

Сервер отдаёт `/health` (для HEALTHCHECK и проверок после выкладки) и `/metrics` в формате Prometheus: активные соединения, счётчики подключений, входов в комнаты, сообщений и отказов по токену, время работы, память JVM. Наружу `/metrics` закрыт в `Caddyfile`. Правила оповещений - `deploy/prometheus/alerts.yml`, отправка в Telegram - `deploy/alertmanager/alertmanager.yml` (указать свой `chat_id`).

## Безопасность

- Trivy в `ci.yml`: проверка Dockerfile и конфигурации, затем уязвимости образа (HIGH и CRITICAL с доступным исправлением роняют сборку).
- Контейнеры: непривилегированный пользователь, `read_only`, `cap_drop: ALL`, `no-new-privileges`, лимиты памяти и числа процессов.
- Наружу опубликованы только Caddy и TURN; Grafana слушает 127.0.0.1.
- `docker-bench.sh` запускается на сервере после настройки, замечания разбираются вручную.

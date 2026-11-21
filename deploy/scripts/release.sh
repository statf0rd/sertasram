#!/usr/bin/env bash
# Выкладка сигналинг-сервера на машине с Docker (VPS или ноутбук).
#
#   release.sh staging <image>   поднять проверочный контейнер на тестовом порту
#   release.sh promote <image>   заменить рабочий контейнер; при неудаче - автоматический откат
#   release.sh rollback          вернуть предыдущий рабочий образ
#   release.sh status            что сейчас запущено
#
# Запускается из каталога deploy (рядом docker-compose.yml и .env).
# Набор compose-файлов берётся из COMPOSE_FILE в .env (по умолчанию docker-compose.yml).
# COMPOSE_ARGS позволяет задать его разово, например для ноутбука:
#   COMPOSE_ARGS="-f docker-compose.yml -f docker-compose.local.yml" ./scripts/release.sh promote sertas-signaling:local
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE_ARGS=${COMPOSE_ARGS:-}
STAGING_NAME=sertas-signaling-staging
STAGING_PORT=${STAGING_PORT:-18080}
STATE_DIR=.release
mkdir -p "$STATE_DIR"

# COMPOSE_ARGS намеренно разбивается на слова: это список аргументов "-f файл -f файл".
# shellcheck disable=SC2086
compose() { docker compose $COMPOSE_ARGS "$@"; }

# Ждёт, пока HEALTHCHECK контейнера станет healthy (до 60 с).
wait_healthy() {
  local container=$1
  for _ in $(seq 1 30); do
    case "$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || echo missing)" in
      healthy) return 0 ;;
      unhealthy) break ;;
    esac
    sleep 2
  done
  echo "Контейнер $container не стал healthy. Последние строки лога:" >&2
  docker logs --tail 30 "$container" >&2 || true
  return 1
}

set_image() {
  local image=$1
  if grep -q '^SIGNALING_IMAGE=' .env; then
    sed -i.bak "s|^SIGNALING_IMAGE=.*|SIGNALING_IMAGE=$image|" .env && rm -f .env.bak
  else
    echo "SIGNALING_IMAGE=$image" >> .env
  fi
}

current_image() {
  local id
  id=$(compose ps -q signaling 2>/dev/null || true)
  [ -n "$id" ] && docker inspect -f '{{.Config.Image}}' "$id" || true
}

start_signaling() {
  compose up -d --no-deps signaling
  wait_healthy "$(compose ps -q signaling)"
}

case "${1:-}" in
  staging)
    image=${2:?укажите образ}
    docker rm -f "$STAGING_NAME" >/dev/null 2>&1 || true
    # Тот же образ и те же ограничения, что у рабочего контейнера, но отдельный порт и свой токен.
    docker run -d --name "$STAGING_NAME" \
      --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true \
      --memory 384m --pids-limit 256 \
      -e SERTAS_TOKEN="${STAGING_TOKEN:?задайте STAGING_TOKEN}" \
      -p "${STAGING_BIND:-0.0.0.0}:$STAGING_PORT:8080" \
      "$image" >/dev/null
    wait_healthy "$STAGING_NAME"
    echo "staging: $image на порту $STAGING_PORT"
    ;;

  promote)
    image=${2:?укажите образ}
    previous=$(current_image)
    echo "promote: $previous -> $image"
    set_image "$image"
    if start_signaling; then
      # Предыдущий образ запоминается только после удачной замены:
      # неудачная попытка не должна затирать точку отката.
      [ -n "$previous" ] && echo "$previous" > "$STATE_DIR/previous_image"
      echo "$image" > "$STATE_DIR/current_image"
      docker rm -f "$STAGING_NAME" >/dev/null 2>&1 || true
      echo "promote: готово"
    else
      echo "promote: новый образ не прошёл проверку, откат" >&2
      if [ -n "$previous" ]; then
        set_image "$previous"
        start_signaling && echo "rollback: возвращён $previous" >&2
      fi
      exit 1
    fi
    ;;

  rollback)
    previous=$(cat "$STATE_DIR/previous_image" 2>/dev/null || true)
    [ -n "$previous" ] || { echo "rollback: предыдущий образ не записан" >&2; exit 1; }
    bad=$(current_image)
    echo "rollback: $bad -> $previous"
    set_image "$previous"
    start_signaling
    echo "$previous" > "$STATE_DIR/current_image"
    echo "$bad" > "$STATE_DIR/previous_image"
    ;;

  status)
    echo "рабочий образ:    $(current_image)"
    echo "предыдущий образ: $(cat "$STATE_DIR/previous_image" 2>/dev/null || echo -)"
    compose ps
    docker ps --filter "name=$STAGING_NAME" --format 'staging: {{.Image}} {{.Status}}'
    ;;

  *)
    sed -n '2,12p' "$0"; exit 2 ;;
esac

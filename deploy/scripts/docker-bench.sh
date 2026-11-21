#!/usr/bin/env bash
# Проверка хоста и контейнеров по CIS Docker Benchmark (docker-bench-security).
# Запускать на сервере: ./scripts/docker-bench.sh > docker-bench.log
set -euo pipefail

# Готовый образ docker/docker-bench-security собран только под amd64,
# на остальных архитектурах (ARM-сервер) он собирается из официального репозитория.
IMAGE=docker/docker-bench-security
if [ "$(uname -m)" != x86_64 ]; then
  IMAGE=docker-bench-security:local
  docker build -q -t "$IMAGE" https://github.com/docker/docker-bench-security.git >&2
fi

docker run --rm --net host --pid host --userns host --cap-add audit_control \
  -e DOCKER_CONTENT_TRUST="${DOCKER_CONTENT_TRUST:-}" \
  -v /etc:/etc:ro \
  -v /var/lib:/var/lib:ro \
  -v /var/run/docker.sock:/var/run/docker.sock:ro \
  --label docker_bench_security \
  "$IMAGE" "$@"

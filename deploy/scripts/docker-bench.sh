#!/usr/bin/env bash
# Проверка хоста и контейнеров по CIS Docker Benchmark (docker-bench-security).
# Запускать на сервере: ./scripts/docker-bench.sh > docker-bench.log
set -euo pipefail
docker run --rm --net host --pid host --userns host --cap-add audit_control \
  -e DOCKER_CONTENT_TRUST="${DOCKER_CONTENT_TRUST:-}" \
  -v /etc:/etc:ro \
  -v /var/lib:/var/lib:ro \
  -v /var/run/docker.sock:/var/run/docker.sock:ro \
  --label docker_bench_security \
  docker/docker-bench-security "$@"

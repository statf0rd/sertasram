#!/usr/bin/env bash
# Сколько РАЗНЫХ кадров в записи экрана получателя: повторы (застывшая картинка,
# дубли от рекордера) выбрасывает mpdecimate. Эффективный FPS = уникальные / длительность.
#
#   ./scripts/measure/count-fps.sh запись.mov [начало_с] [длительность_с]
#
# Для честного сравнения обрезайте начало и конец (подключение, переключение окон):
# например, 10 30 - взять 30 с, начиная с 10-й.
set -euo pipefail

IN="${1:?укажите файл записи}"
START="${2:-0}"
DUR="${3:-}"
FFMPEG="${FFMPEG:-ffmpeg}"
FFPROBE="${FFPROBE:-ffprobe}"

TRIM=(-ss "$START")
[ -n "$DUR" ] && TRIM+=(-t "$DUR")
if [ -z "$DUR" ]; then
    FULL=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$IN")
    DUR=$(awk -v f="$FULL" -v s="$START" 'BEGIN { printf "%.3f", f - s }')
fi

# Кадры считаем по одному на строку framecrc; passthrough не даёт ffmpeg
# досыпать выброшенные кадры обратно ради постоянного FPS.
frames() {
    "$FFMPEG" -hide_banner -loglevel error "${TRIM[@]}" -i "$IN" "$@" -an -fps_mode passthrough \
        -f framecrc - | grep -vc '^#' || true
}
TOTAL=$(frames)
UNIQUE=$(frames -vf mpdecimate)

awk -v u="$UNIQUE" -v t="$TOTAL" -v s="$DUR" -v f="$IN" 'BEGIN {
    printf "%s\n  кадров в записи: %d за %.1f с (%.1f к/с)\n", f, t, s, (s > 0 ? t / s : 0)
    printf "  уникальных: %d -> эффективный FPS %.1f\n", u, (s > 0 ? u / s : 0)
}'

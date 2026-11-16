#!/usr/bin/env bash
# Отчёт по записи звука у получателя: уровни по полосам (где кончается спектр),
# сохранилось ли стерео, и картинка спектрограммы рядом с файлом.
#
#   ./scripts/measure/audio-report.sh запись.wav [начало_с] [длительность_с]
#
# Полосы: RMS сигнала выше 4, 8, 12, 16 и 19 кГц относительно всего сигнала.
# Если выше какой-то частоты уровень упал на 40+ дБ ниже, чем в эталоне, - там
# кодек или шумоподавление срезали спектр.
# Стерео: уровень разности L-R. Если она на 30+ дБ тише каналов, звук пришёл моно.
set -euo pipefail

IN="${1:?укажите файл записи}"
START="${2:-0}"
DUR="${3:-}"
FFMPEG="${FFMPEG:-ffmpeg}"
TRIM=(-ss "$START")
[ -n "$DUR" ] && TRIM+=(-t "$DUR")

# RMS (дБ) после цепочки фильтров; -inf для тишины.
rms() {
    "$FFMPEG" -hide_banner -nostats "${TRIM[@]}" -i "$IN" -af "$1,astats=measure_perchannel=none" \
        -f null - 2>&1 | awk -F': ' '/Overall/ { o = 1 } o && /RMS level dB/ { print $2; exit }'
}

MONO="pan=mono|c0=0.5*c0+0.5*c1"
echo "$IN"
printf "  %-22s %8s дБ\n" "весь сигнал" "$(rms "$MONO")"
for f in 4000 8000 12000 16000 19000; do
    # два фильтра подряд - спад круче, меньше утечки ниже среза
    printf "  %-22s %8s дБ\n" "выше $((f / 1000)) кГц" "$(rms "$MONO,highpass=f=$f,highpass=f=$f")"
done
printf "  %-22s %8s дБ\n" "левый канал" "$(rms 'pan=mono|c0=c0')"
printf "  %-22s %8s дБ\n" "правый канал" "$(rms 'pan=mono|c0=c1')"
printf "  %-22s %8s дБ\n" "разность L-R" "$(rms 'pan=mono|c0=c0-c1')"

PNG="${IN%.*}-spectrum.png"
"$FFMPEG" -hide_banner -loglevel error -y "${TRIM[@]}" -i "$IN" \
    -lavfi "showspectrumpic=s=1600x900:mode=separate:scale=log:legend=1" "$PNG"
echo "  спектрограмма: $PNG"

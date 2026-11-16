#!/usr/bin/env bash
# Генерирует эталонные материалы для сравнительных замеров (см. docs/measurements.md).
# Всё синтезируется ffmpeg'ом - чужой музыки нет, файлы можно свободно раздавать.
# В git не кладутся: генерируются по требованию.
#
#   ./scripts/measure/make-test-media.sh [КАТАЛОГ]   (по умолчанию build/test-media)
#
# Файлы (звук - стерео 48 кГц, 24 бит):
#   sweep-20hz-20khz.wav  логарифмический свип 20 Гц - 20 кГц, 20 с: верхняя граница спектра
#   lr-check.wav          1 кГц только слева, только справа, потом 440 Гц слева + 660 Гц справа
#   music-stereo.wav      синтезированный стерео-фрагмент 30 с: аккорды, бас, шейкер, мелодия по кругу L-R
#   video-60fps.mp4       1920x1080 60 к/с, testsrc со счётчиком и бегущим градиентом, 60 с
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$ROOT/build/test-media}"
FFMPEG="${FFMPEG:-ffmpeg}"
command -v "$FFMPEG" >/dev/null || { echo "нужен ffmpeg (brew install ffmpeg)"; exit 1; }
mkdir -p "$OUT"

RATE=48000
WAV=(-ar "$RATE" -c:a pcm_s24le)
ff() { "$FFMPEG" -hide_banner -loglevel error -y "$@"; }

echo "[1/4] свип 20 Гц - 20 кГц"
# Логарифмический свип: f(t) = 20 * 1000^(t/T), фаза - интеграл частоты.
T=20
SWEEP="0.5*sin(2*PI*20*$T/log(1000)*(exp(t/$T*log(1000))-1))"
ff -f lavfi -i "aevalsrc=exprs='$SWEEP|$SWEEP':s=$RATE:d=$T" \
   -af "afade=t=in:d=0.05,afade=t=out:st=$((T - 1)).95:d=0.05" "${WAV[@]}" "$OUT/sweep-20hz-20khz.wav"

echo "[2/4] проверка каналов L/R"
# 0-2 с: 1 кГц только слева; 3-5 с: только справа; 6-10 с: 440 Гц слева и 660 Гц справа.
# После приёма в моно оба тона окажутся в обоих каналах.
L="0.5*sin(2*PI*1000*t)*between(t,0,2)+0.5*sin(2*PI*440*t)*between(t,6,10)"
R="0.5*sin(2*PI*1000*t)*between(t,3,5)+0.5*sin(2*PI*660*t)*between(t,6,10)"
ff -f lavfi -i "aevalsrc=exprs='$L|$R':s=$RATE:d=10" "${WAV[@]}" "$OUT/lr-check.wav"

echo "[3/4] стерео-фрагмент 30 с"
# Аккорды C-Am-F-G по 2 с (щипок каждые 0,5 с), нижний голос слева, верхний справа;
# бас по центру; шейкер (шум, широкий спектр) на слабые доли, в каналах разный;
# мелодия по пентатонике, которая медленно ходит слева направо и обратно.
N="mod(floor(t/2),4)"
R1="if(eq($N,0),261.63,if(eq($N,1),220,if(eq($N,2),174.61,196)))"
R2="if(eq($N,0),329.63,if(eq($N,1),261.63,if(eq($N,2),220,246.94)))"
R3="if(eq($N,0),392,if(eq($N,1),329.63,if(eq($N,2),261.63,293.66)))"
tone() { echo "(sin(2*PI*($1)*t)+0.5*sin(4*PI*($1)*t)+0.25*sin(6*PI*($1)*t))"; }
ENV="exp(-4*mod(t,0.5))"
BASS="sin(PI*($R1)*t)*exp(-1.5*mod(t,2))"
K="mod(floor(t/0.25),8)"
LEADF="if(eq($K,0),523.25,if(eq($K,1),587.33,if(eq($K,2),659.25,if(eq($K,3),783.99,if(eq($K,4),880,if(eq($K,5),783.99,if(eq($K,6),659.25,587.33)))))))"
LEAD="sin(2*PI*($LEADF)*t)*exp(-6*mod(t,0.25))"
PAN="(0.5+0.5*sin(2*PI*t/8))"
HATL="(random(0)*2-1)*exp(-50*mod(t+0.25,0.5))"
HATR="(random(1)*2-1)*exp(-50*mod(t+0.25,0.5))"
ML="0.12*$ENV*($(tone "$R1")+0.6*$(tone "$R2")+0.3*$(tone "$R3"))+0.25*$BASS+0.1*$LEAD*(1-$PAN)+0.08*$HATL"
MR="0.12*$ENV*(0.3*$(tone "$R1")+0.6*$(tone "$R2")+$(tone "$R3"))+0.25*$BASS+0.1*$LEAD*$PAN+0.12*$HATR"
ff -f lavfi -i "aevalsrc=exprs='$ML|$MR':s=$RATE:d=30" \
   -af "afade=t=in:d=0.02,afade=t=out:st=29.5:d=0.5" "${WAV[@]}" "$OUT/music-stereo.wav"

echo "[4/4] видео 60 к/с со счётчиком"
# testsrc: в каждом кадре своё время (2 знака после запятой) и сдвинутый градиент,
# поэтому повторы кадров у получателя ловит mpdecimate (scripts/measure/count-fps.sh).
ff -f lavfi -i "testsrc=size=1920x1080:rate=60:decimals=2" -t 60 \
   -c:v libx264 -preset veryfast -crf 16 -pix_fmt yuv420p -g 60 "$OUT/video-60fps.mp4"

echo "готово -> $OUT"
ls -lh "$OUT"

#!/usr/bin/env bash
# Собирает sertasram.app для macOS с вложенной JRE и нативными библиотеками.
# По умолчанию Apple Silicon (arm64); ARCH=x86_64 — Intel (кросс-сборка с
# Apple Silicon). Адрес сервера/TURN берутся из env (не хардкод в репозитории).
#
#   SERTAS_SERVER='ws://...' SERTAS_TURN='turn:...' ./scripts/package-macos.sh
#   -> ~/Desktop/sertasram-macos.zip  (внутри sertasram.app)
#   ARCH=x86_64 ... ./scripts/package-macos.sh -> ~/Desktop/sertasram-macos-intel.zip
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARCH="${ARCH:-aarch64}"
case "$ARCH" in
  aarch64) JFX_CLS=mac-aarch64; JRE_ARCH=aarch64; SUFFIX="";        DYLIB_DIR="$ROOT/media-engine/build/native" ;;
  x86_64)  JFX_CLS=mac;         JRE_ARCH=x64;     SUFFIX="-intel";  DYLIB_DIR="$ROOT/media-engine/build/native/x86_64" ;;
  *) echo "ARCH должен быть aarch64 или x86_64"; exit 1 ;;
esac
STAGE=/tmp/sertasram-macos$SUFFIX
OUT="${OUT:-$HOME/Desktop/sertasram-macos$SUFFIX.zip}"
JFX=21.0.4
WEBRTC=0.14.0
JACKSON=2.17.2
MC=https://repo1.maven.org/maven2
SERVER_URL="${SERTAS_SERVER:-ws://localhost:8080/signal}"
TURN_SPEC="${SERTAS_TURN:-}"

echo "[1/5] building module jars"
"$ROOT/gradlew" -p "$ROOT" :app-client:jar :media-engine:jar :media:jar :signaling-client:jar :protocol:jar -q

APP="$STAGE/sertasram.app"
RES="$APP/Contents/Resources"
echo "[2/5] app skeleton + lib/"
rm -rf "$STAGE"; mkdir -p "$APP/Contents/MacOS" "$RES/lib" "$RES/jre"
cp "$ROOT"/{app-client,media-engine,media,signaling-client,protocol}/build/libs/*.jar "$RES/lib/"
CACHE="$HOME/.gradle/caches"
for art in "jackson-databind-$JACKSON" "jackson-core-$JACKSON" "jackson-annotations-$JACKSON" "webrtc-java-$WEBRTC"; do
  f=$(find "$CACHE" -name "$art.jar" | grep -vE 'sources|javadoc' | head -1)
  cp "$f" "$RES/lib/"
done

echo "[2b/5] native audio dylib (ScreenCaptureKit, $ARCH)"
if [ "$ARCH" = x86_64 ]; then ARCH=x86_64 "$ROOT/scripts/build-macos-audio-dylib.sh"; else "$ROOT/scripts/build-macos-audio-dylib.sh"; fi
cp "$DYLIB_DIR/libsertas_audio.dylib" "$RES/lib/"

echo "[3/5] downloading macOS $ARCH native jars"
curl -fsSL "$MC/dev/onvoid/webrtc/webrtc-java/$WEBRTC/webrtc-java-$WEBRTC-macos-$ARCH.jar" -o "$RES/lib/webrtc-java-$WEBRTC-macos-$ARCH.jar"
for m in base graphics controls; do
  curl -fsSL "$MC/org/openjfx/javafx-$m/$JFX/javafx-$m-$JFX-$JFX_CLS.jar" -o "$RES/lib/javafx-$m-$JFX-$JFX_CLS.jar"
done

echo "[4/5] downloading macOS $ARCH JRE (Temurin 21)"
JRE_TMP=/tmp/jre-mac$SUFFIX
curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/mac/$JRE_ARCH/jre/hotspot/normal/eclipse" -o "$JRE_TMP.tar.gz"
rm -rf "$JRE_TMP-extract"; mkdir -p "$JRE_TMP-extract"
tar -xzf "$JRE_TMP.tar.gz" -C "$JRE_TMP-extract"
INNER=$(ls -d "$JRE_TMP-extract"/*/ | head -1)
cp -R "$INNER"Contents "$RES/jre/Contents"

echo "[5/5] Info.plist + launcher + zip"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>sertasram</string>
  <key>CFBundleDisplayName</key><string>sertasram</string>
  <key>CFBundleIdentifier</key><string>dev.sertas.app</string>
  <key>CFBundleVersion</key><string>1.0</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleExecutable</key><string>sertasram</string>
  <key>NSMicrophoneUsageDescription</key><string>Голосовая связь в звонке</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
</dict>
</plist>
PLIST

TURN_LINE=""
[ -n "$TURN_SPEC" ] && TURN_LINE="  -Dsertas.turn=\"$TURN_SPEC\" \\"
# Звук демонстрации (ScreenCaptureKit) — включён в бандле по умолчанию;
# SERTAS_DEMOAUDIO=off чтобы выключить. Без флага CallController.join не
# создаёт ни audioEngine (отдача), ни demoPlayer (приём) — звук демо
# не работает в обе стороны (зеркально package-windows.sh).
DEMOAUDIO="${SERTAS_DEMOAUDIO:-on}"
cat > "$APP/Contents/MacOS/sertasram" <<LAUNCH
#!/bin/bash
HERE="\$(cd "\$(dirname "\$0")/../Resources" && pwd)"
LOG="\$HOME/Library/Logs/sertasram.log"   # лог приложения (предыдущий запуск -> sertasram.log.prev)
mkdir -p "\$(dirname "\$LOG")"; [ -f "\$LOG" ] && mv -f "\$LOG" "\$LOG.prev"
exec "\$HERE/jre/Contents/Home/bin/java" \\
  -Dsertas.server="$SERVER_URL" \\
  -Dsertas.audio.dylib="\$HERE/lib/libsertas_audio.dylib" \\
  -Dsertas.demoaudio=$DEMOAUDIO \\
$TURN_LINE
  -cp "\$HERE/lib/*" dev.sertas.app.Launcher >>"\$LOG" 2>&1
LAUNCH
chmod +x "$APP/Contents/MacOS/sertasram"
chmod +x "$RES/jre/Contents/Home/bin/java" 2>/dev/null || true

rm -f "$OUT"
( cd "$STAGE" && zip -q -r -y "$OUT" sertasram.app )
echo "done -> $OUT ($(du -h "$OUT" | cut -f1))"
echo "ВАЖНО: при обновлении удалите старый sertasram.app и распакуйте архив на его место."
echo "Иначе Finder создаст копию «sertasram 2.app», а разрешение Screen Recording выдано"
echo "старому пути — macOS попросит доступ заново (бандл не подписан, TCC различает копии)."
echo "Переход со старого sertas.app: удалите его — после переименования доступ к записи экрана нужно выдать один раз заново."

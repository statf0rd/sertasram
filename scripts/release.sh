#!/usr/bin/env bash
# Сборка и публикация релиза на GitHub в два шага: сначала собрать и проверить
# сборки руками (docs/checklist.md), потом выложить ровно их.
#
#   ./scripts/release.sh build 0.3.0             # -> dist/0.3.0
#   ./scripts/release.sh publish 0.3.0 notes.md  # релиз v0.3.0 из dist/0.3.0
#
# build собирает из закоммиченного кода zip и dmg для macOS (Apple Silicon и
# Intel) и zip для Windows. Установщик Windows после публикации собирает и
# прикладывает к релизу workflow windows-installer (Inno Setup на windows-runner).
#
# Адрес сервера и TURN в публичный релиз не вшиваются: пользователь вводит
# адрес с токеном в поле «Сервер». Нужны gh (авторизованный), curl, zip, Xcode CLT.
set -euo pipefail

CMD="${1:-}"
VERSION="${2:-}"
VERSION="${VERSION#v}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist/$VERSION"
TAG="v$VERSION"
ASSETS=(stars-macos-arm64.zip stars-macos-arm64.dmg
        stars-macos-x64.zip stars-macos-x64.dmg
        stars-windows-x64.zip)

usage() { sed -n '2,8p' "$0"; exit 1; }
[ -n "$VERSION" ] || usage

case "$CMD" in
  build)
    if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then
      echo "в рабочей копии есть незакоммиченные изменения - релиз собирается только из коммита"; exit 1
    fi
    unset SERTAS_SERVER SERTAS_TURN SERTAS_JVM_EXTRA
    rm -rf "$DIST"; mkdir -p "$DIST"
    VERSION="$VERSION" OUT="$DIST/stars-macos-arm64.zip" "$ROOT/scripts/package-macos.sh"
    VERSION="$VERSION" ARCH=x86_64 OUT="$DIST/stars-macos-x64.zip" "$ROOT/scripts/package-macos.sh"
    OUT="$DIST/stars-windows-x64.zip" "$ROOT/scripts/package-windows.sh"
    git -C "$ROOT" rev-parse HEAD > "$DIST/COMMIT"
    ( cd "$DIST" && shasum -a 256 "${ASSETS[@]}" )
    echo "Собрано в $DIST. Проверьте сборки, затем: $0 publish $VERSION notes.md"
    ;;
  publish)
    NOTES="${3:?укажите файл с описанием релиза}"
    [ -f "$NOTES" ] || { echo "нет файла $NOTES"; exit 1; }
    for a in "${ASSETS[@]}" COMMIT; do
      [ -f "$DIST/$a" ] || { echo "нет $DIST/$a - сначала $0 build $VERSION"; exit 1; }
    done
    COMMIT="$(cat "$DIST/COMMIT")"
    git -C "$ROOT" fetch -q origin
    if [ -z "$(git -C "$ROOT" branch -r --contains "$COMMIT")" ]; then
      echo "коммит $COMMIT, из которого собраны сборки, не запушен на GitHub"; exit 1
    fi
    if gh release view "$TAG" >/dev/null 2>&1; then
      echo "релиз $TAG уже есть"; exit 1
    fi
    ( cd "$DIST" && gh release create "$TAG" --target "$COMMIT" --title "stars $TAG" \
        --notes-file "$NOTES" "${ASSETS[@]}" )
    echo "Релиз $TAG опубликован. Установщик Windows соберёт workflow windows-installer:"
    echo "  gh run list --workflow windows-installer.yml --limit 1"
    ;;
  *) usage ;;
esac

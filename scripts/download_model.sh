#!/usr/bin/env bash
# download_model.sh
# Downloads the Depth Anything V2 ViT-S quantized ONNX model from the tiefling project.
#
# Usage (from ParallaxWallpaper/ root):
#   bash scripts/download_model.sh

set -euo pipefail

MODEL="depthanythingv2-vits-dynamic-quant.onnx"
DEST_DIR="$(dirname "$0")/../app/src/main/assets"
DEST="$DEST_DIR/$MODEL"

URL_MEDIA="https://media.githubusercontent.com/media/combatwombat/tiefling/main/site/public/models/$MODEL"
URL_RAW="https://github.com/combatwombat/tiefling/raw/main/site/public/models/$MODEL"

mkdir -p "$DEST_DIR"

if [[ -f "$DEST" ]]; then
    SIZE=$(wc -c < "$DEST")
    if (( SIZE > 1000000 )); then
        echo "[OK] Model already present ($(( SIZE / 1024 / 1024 )) MB). Nothing to do."
        exit 0
    fi
    echo "[WARN] Existing file looks incomplete ($SIZE bytes). Re-downloading..."
    rm -f "$DEST"
fi

echo "Downloading ~26 MB from tiefling repo..."

if command -v curl &>/dev/null; then
    curl -L --progress-bar -o "$DEST" "$URL_MEDIA" \
        || curl -L --progress-bar -o "$DEST" "$URL_RAW"
elif command -v wget &>/dev/null; then
    wget -q --show-progress -O "$DEST" "$URL_MEDIA" \
        || wget -q --show-progress -O "$DEST" "$URL_RAW"
else
    echo "ERROR: neither curl nor wget found. Please download manually:"
    echo "  $URL_MEDIA"
    echo "  -> $DEST"
    exit 1
fi

SIZE=$(wc -c < "$DEST")
echo ""
echo "[DONE] Saved $(( SIZE / 1024 / 1024 )) MB to $DEST"
echo "You can now build and run the app."

#!/usr/bin/env bash
# Downloads the ONNX embedding model and its tokenizer used by Ask AI (all-MiniLM-L6-v2, 384 dimensions, about 90 MB)
# into backend/models/ (git-ignored), so the application works offline afterwards.
#
#   ./scripts/fetch-embedding-model.sh [target-dir]      default target: models/all-MiniLM-L6-v2
#
# Then point the application at the files (the defaults download and cache them on first use anyway, so this script
# is only needed to prefetch, or for machines that cannot reach huggingface.co when the application starts):
#   EMBEDDING_MODEL_URI=file:models/all-MiniLM-L6-v2/model.onnx
#   EMBEDDING_TOKENIZER_URI=file:models/all-MiniLM-L6-v2/tokenizer.json
#
# Note: the jar of spring-ai-transformers contains only a pointer stub for model.onnx (133 bytes), not the model.
set -euo pipefail

BASE_URL="${EMBEDDING_BASE_URL:-https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main}"
cd "$(dirname "$0")/.."
TARGET="${1:-models/all-MiniLM-L6-v2}"
mkdir -p "$TARGET"

fetch() { # remote path, local name
  if [ -s "$TARGET/$2" ]; then
    echo "already present: $TARGET/$2"
    return
  fi
  echo "downloading $1 -> $TARGET/$2"
  curl -fL --retry 3 --progress-bar -o "$TARGET/$2.part" "$BASE_URL/$1"
  mv "$TARGET/$2.part" "$TARGET/$2"
}

fetch onnx/model.onnx model.onnx
fetch tokenizer.json tokenizer.json

SIZE=$(wc -c < "$TARGET/model.onnx")
if [ "$SIZE" -lt 10000000 ]; then
  echo "model.onnx is only $SIZE bytes: that is not the model (a pointer file?). Delete it and retry." >&2
  exit 1
fi
echo "done: $TARGET"

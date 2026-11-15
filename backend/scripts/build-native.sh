#!/usr/bin/env bash
# Builds the Tree-sitter native libraries that jtreesitter loads through the FFM API:
#   libtree-sitter            the runtime
#   libtree-sitter-<grammar>  one library per language grammar (see GRAMMARS below)
# Output goes to backend/native/ (git-ignored), the default of archyra.parsing.native-library-dir.
# Needs: git, make and a C compiler (cc); a C++ compiler (c++) for grammars with a C++ scanner.
#
#   ./scripts/build-native.sh [output-dir]      build everything
#   GRAMMARS="python go" ./scripts/build-native.sh   build only the runtime and these grammars
#
# To add a language: add a line to GRAMMARS_TABLE (name|repository|tag|source directory), then an
# extractor in com.abhiraj.archyra.parsing.lang.
set -euo pipefail

TREE_SITTER_VERSION="v0.27.0"        # keep in line with the jtreesitter version in pom.xml

# name | git repository | tag | directory that holds parser.c (relative to the repository)
GRAMMARS_TABLE='
java       | https://github.com/tree-sitter/tree-sitter-java       | v0.23.5  | src
python     | https://github.com/tree-sitter/tree-sitter-python     | v0.23.6  | src
javascript | https://github.com/tree-sitter/tree-sitter-javascript | v0.23.1  | src
typescript | https://github.com/tree-sitter/tree-sitter-typescript | v0.23.2  | typescript/src
tsx        | https://github.com/tree-sitter/tree-sitter-typescript | v0.23.2  | tsx/src
go         | https://github.com/tree-sitter/tree-sitter-go         | v0.23.4  | src
rust       | https://github.com/tree-sitter/tree-sitter-rust       | v0.24.0  | src
c-sharp    | https://github.com/tree-sitter/tree-sitter-c-sharp    | v0.23.1  | src
php        | https://github.com/tree-sitter/tree-sitter-php        | v0.24.2  | php/src
ruby       | https://github.com/tree-sitter/tree-sitter-ruby       | v0.23.1  | src
kotlin     | https://github.com/fwcd/tree-sitter-kotlin            | 0.3.8    | src
solidity   | https://github.com/JoranHonig/tree-sitter-solidity    | v1.2.13  | src
'

case "$(uname -s)" in
  Darwin) EXT="dylib" ;;
  Linux)  EXT="so" ;;
  *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac

BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$BACKEND_DIR/native}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
mkdir -p "$OUT_DIR"

echo "==> Building tree-sitter runtime $TREE_SITTER_VERSION"
git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$TREE_SITTER_VERSION" https://github.com/tree-sitter/tree-sitter.git "$WORK_DIR/tree-sitter"
make -C "$WORK_DIR/tree-sitter" -j4 "libtree-sitter.$EXT" >/dev/null
cp "$WORK_DIR/tree-sitter/libtree-sitter.$EXT" "$OUT_DIR/"

wanted() { [ -z "${GRAMMARS:-}" ] || [[ " $GRAMMARS " == *" $1 "* ]]; }

build_grammar() {
  local name="$1" repository="$2" tag="$3" source_dir="$4"
  local checkout="$WORK_DIR/$(basename "$repository")-$tag"
  if [ ! -d "$checkout" ]; then
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$tag" "$repository.git" "$checkout"
  fi
  local src="$checkout/$source_dir"
  local compiler="cc"
  local objects=()
  for file in "$src"/*.c "$src"/*.cc; do
    [ -e "$file" ] || continue
    [[ "$file" == *.cc ]] && compiler="c++"
    local object="$WORK_DIR/$name-$(basename "$file").o"
    if [[ "$file" == *.cc ]]; then
      c++ -fPIC -O2 -std=c++14 -I "$src" -I "$checkout/common" -c "$file" -o "$object"
    else
      cc -fPIC -O2 -I "$src" -I "$checkout/common" -c "$file" -o "$object"
    fi
    objects+=("$object")
  done
  "$compiler" -shared "${objects[@]}" -o "$OUT_DIR/libtree-sitter-$name.$EXT"
}

while IFS='|' read -r name repository tag source_dir; do
  name="$(echo "$name" | xargs)"; [ -z "$name" ] && continue
  repository="$(echo "$repository" | xargs)"; tag="$(echo "$tag" | xargs)"; source_dir="$(echo "$source_dir" | xargs)"
  wanted "$name" || continue
  echo "==> Building tree-sitter-$name $tag"
  build_grammar "$name" "$repository" "$tag" "$source_dir"
done <<< "$GRAMMARS_TABLE"

echo "==> Done:"
ls -l "$OUT_DIR"

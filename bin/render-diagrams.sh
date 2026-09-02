#!/usr/bin/env bash
#
# Renders every Mermaid block of every Markdown file of this repository and fails on the
# first one which does not parse.
#
# A block which does not parse is invisible: GitHub and the IntelliJ plugin both put an
# error message where the picture should be, and both times it happened here nobody
# noticed for weeks. mmdc is the renderer those two use, and it fails loudly on what they
# fail on quietly.
#
#   bin/render-diagrams.sh            # everything
#   bin/render-diagrams.sh a.md b.md  # the files you changed
#
# It needs Node and downloads the renderer plus a headless browser on first use, which is
# why the Maven build does not call it: a local build has to work offline. The pull
# request runs it, and diagrams/README.md says what the semicolon trap is.

set -euo pipefail

cd "$(dirname "$0")/.."

files=("$@")
if [ ${#files[@]} -eq 0 ]; then
  while IFS= read -r file; do
    files+=("$file")
  done < <(grep -rl --include='*.md' '```mermaid' . | grep -v '/node_modules/' | sort)
fi

if [ ${#files[@]} -eq 0 ]; then
  echo "No Markdown file holds a Mermaid block."
  exit 0
fi

# mmdc writes one SVG per block next to its output file, so the output goes to a
# directory of its own and the repository stays clean
rendered=$(mktemp -d)
trap 'rm -rf "$rendered"' EXIT

# The renderer's browser gets --no-sandbox because a GitHub runner offers it no usable
# sandbox and it refuses to start without one. What it opens are the Markdown files of
# this repository, so there is no untrusted content the sandbox would be protecting us
# from. The file is written here rather than kept in the repository, so it cannot drift
# away from the call using it.
cat > "$rendered/puppeteer.json" <<'JSON'
{ "args": ["--no-sandbox", "--disable-setuid-sandbox"] }
JSON

status=0
for file in "${files[@]}"; do
  blocks=$(grep -c '```mermaid' "$file" || true)
  echo "--- $file ($blocks)"
  if ! output=$(npx -y -p @mermaid-js/mermaid-cli mmdc \
      -p "$rendered/puppeteer.json" \
      -i "$file" \
      -o "$rendered/$(echo "${file#./}" | tr / _)" 2>&1); then
    printf '%s\n' "$output" >&2
    if printf '%s' "$output" | grep -q 'Failed to launch the browser process'; then
      echo "The renderer did not start, so no block of '$file' was checked" >&2
    else
      echo "'$file' holds a Mermaid block which does not parse - see the traps in diagrams/README.md" >&2
    fi
    status=1
  fi
done

exit $status

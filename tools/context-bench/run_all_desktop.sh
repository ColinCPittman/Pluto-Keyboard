#!/bin/sh
# Runs bench_desktop.py for every preset in models.json (or those given as args).
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${PY:-/d/FUTO-models/context/venv/Scripts/python}
SERVER=${SERVER:-/d/FUTO-models/context/llama-win/llama-server.exe}
MODES=${MODES:-rewrite,edits,sent-rewrite,sent-edits}
names=${*:-$(cd "$HERE" && $PY -c "import json;print(' '.join(json.load(open('models.json'))))")}
for n in $names; do
  echo "=== $n"
  $PY "$HERE/bench_desktop.py" --server "$SERVER" --preset "$n" --modes "$MODES" $EXTRA || echo "FAILED $n"
done

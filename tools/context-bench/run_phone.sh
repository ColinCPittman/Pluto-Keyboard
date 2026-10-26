#!/bin/sh
# Phone latency benchmark for one model preset (see make_phone_prompts.py).
#   ./run_phone.sh <preset> [variants]
# Pushes the GGUF + prompt files to /data/local/tmp/ctxbench, runs ctxrun variants
# pinned to the two Cortex-A78 cores (cpu6,7), pulls JSONL results, deletes the model.
set -e
P=$1
VARIANTS=${2:-"rw_spec rw_plain edits rw_nocache rw_spec_t4"}
SERIAL=${SERIAL:-5003AF2010009554}
GGUF=${GGUF:-/d/FUTO-models/context/gguf}
PH=${PH:-/d/FUTO-models/context/phone}
D=/data/local/tmp/ctxbench
PY=${PY:-/d/FUTO-models/context/venv/Scripts/python}
HERE=$(cd "$(dirname "$0")" && pwd)
F=$(cd "$HERE" && $PY -c "import json;print(json.load(open('models.json'))['$P']['file'])")
A="adb -s $SERIAL"
export MSYS_NO_PATHCONV=1
temp() { $A shell 'dumpsys thermalservice | grep -A1 "Current temperatures" | tail -1' | sed 's/.*mValue=\([0-9.]*\).*/\1/'; }
# small no-cache subset (prefix re-decoded on every request)
(cd "$PH" && $PY -c "
import json; d=json.load(open('$P.latency.rewrite.json',encoding='utf-8'))
d['items']=[i for i in d['items'] if i['id'] in ('w39_owner','w67_l01')]
json.dump(d,open('$P.nocache.rewrite.json','w',encoding='utf-8'))")
$A push "$(cygpath -w "$GGUF/$F")" $D/model.gguf >/dev/null
for x in latency.rewrite latency.edits nocache.rewrite; do $A push "$(cygpath -w "$PH/$P.$x.json")" $D/ >/dev/null; done
mkdir -p "$HERE/results/phone"
for v in $VARIANTS; do
  case $v in
    rw_spec)    args="-f $D/$P.latency.rewrite.json -t 2 --cpus 6,7 --spec 16";;
    rw_plain)   args="-f $D/$P.latency.rewrite.json -t 2 --cpus 6,7";;
    edits)      args="-f $D/$P.latency.edits.json -t 2 --cpus 6,7";;
    rw_nocache) args="-f $D/$P.nocache.rewrite.json -t 2 --cpus 6,7 --spec 16 --no-prefix-cache";;
    rw_spec_t4) args="-f $D/$P.latency.rewrite.json -t 4 --cpus 4,5,6,7 --spec 16";;
    rw_spec_nopin) args="-f $D/$P.latency.rewrite.json -t 2 --spec 16";;
  esac
  t0=$(temp)
  $A shell "cd $D && ./ctxrun -m model.gguf $args" > "$HERE/results/phone/$P.$v.jsonl" 2> "$HERE/results/phone/$P.$v.err" || echo "run $v failed"
  echo "$P $v: cpu ${t0}C -> $(temp)C"
done
$A shell "rm -f $D/model.gguf $D/*.json"

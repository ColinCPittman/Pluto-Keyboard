#!/usr/bin/env bash
# Runs the Kotlin engines on the test inputs in --pure mode and compares with the Python references.
# Needs a JDK whose VC++ runtime is recent enough for onnxruntime 1.31 (see README note in build.gradle),
#        and a python venv with punctuators/onnxruntime/transformers, models under $MODELS.
# usage: run_comparison.sh [--regen-refs]
set -e
MODELS=${MODELS:-/d/FUTO-models/punctuation}
PY=${PY:-/d/FUTO-models/venv-punct/Scripts/python.exe}
cd "$(dirname "$0")"
export PYTHONIOENCODING=utf-8
../../gradlew -p . installDist --console=plain -q
H=build/install/punctuation-harness/bin/punctuation-harness
for set in inputs inputs_long; do
  sfx=""; [ $set = inputs_long ] && sfx="_long"
  if [ "$1" = "--regen-refs" ]; then
    (cd reference && $PY pcs_reference.py $MODELS/pcs_en ../testdata/$set.txt ../testdata/ref_pcs$sfx.txt \
      && $PY horizon_reference.py $MODELS/horizon_small ../testdata/$set.txt ../testdata/ref_horizon$sfx.txt)
  fi
  for e in pcs horizon; do
    d=pcs_en; [ $e = horizon ] && d=horizon_small
    $H batch --engine $e --model-dir "$MODELS/$d" --input testdata/$set.txt --output testdata/kt_${e}${sfx}_pure.txt --pure 2>&1 | grep -v WARN | tail -1
    $PY reference/compare.py testdata/$set.txt testdata/ref_${e}${sfx}.txt testdata/kt_${e}${sfx}_pure.txt --engine $e | tail -12
  done
done

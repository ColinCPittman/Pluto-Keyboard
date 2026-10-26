"""Converts a compact stress batch (batch_NN.txt) to the harness format (batch_NN.jsonl).

One item per line, and blank lines and lines starting with # are skipped:

  c <input>                                   control: must come back unchanged
  p:<cat> <input> => <expected> [;; <alt>]...   positive
  ... @@ Name1, Name2                          personal-dictionary words for this item

In <input>, " | " separates ASR segments (said between pauses): the input is the segments
joined with spaces and "segments" lists them. In <expected>/<alt>, "\\n" is a line break.
For a positive, "=" as <expected> means "unchanged or ..." is not allowed: write the text.

  python -X utf8 mk.py batch_01.txt [more.txt ...]
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def convert(path: Path):
    prefix = path.stem.replace("batch_", "b")
    out = []
    n = 0
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        n += 1
        personal = []
        if " @@ " in line:
            line, p = line.split(" @@ ", 1)
            personal = [x.strip() for x in p.split(",") if x.strip()]
        kind, _, rest = line.partition(" ")
        if kind == "c":
            inp, exp, alts, cat = rest, None, [], "control"
        elif kind.startswith("p:"):
            cat = kind[2:]
            if " => " not in rest:
                raise SystemExit(f"{path}:{n}: positive without =>: {raw}")
            inp, e = rest.split(" => ", 1)
            parts = [x.strip().replace("\\n", "\n") for x in e.split(" ;; ")]
            exp, alts = parts[0], parts[1:]
        else:
            raise SystemExit(f"{path}:{n}: bad kind: {raw}")
        segs = [s.strip() for s in inp.split(" | ")]
        inp = " ".join(segs)
        item = {"id": f"{prefix}_{n:03d}", "cat": cat, "input": inp, "expected": inp if exp is None else exp}
        if alts:
            item["alts"] = alts
        if len(segs) > 1:
            item["segments"] = segs
        if personal:
            item["personal"] = personal
        out.append(item)
    dst = path.with_suffix(".jsonl")
    dst.write_text("".join(json.dumps(i, ensure_ascii=False) + "\n" for i in out), encoding="utf-8")
    nc = sum(1 for i in out if i["cat"] == "control")
    print(f"{dst.name}: {len(out)} items ({nc} controls, {len(out) - nc} positives)")


if __name__ == "__main__":
    for a in sys.argv[1:]:
        convert(Path(a))

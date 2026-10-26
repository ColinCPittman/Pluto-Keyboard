"""Builds a sweep set for Smart corrections for typing: clean sentences from the other test sets
(their "expected" texts), each kept as a control and, where it has a word some slip could hit,
once with that slip injected (a word swapped for one it's a one-key slip of).

  python make_typing_sweep.py <typing-confusions.txt> [--n N] [--seed S]
    -> tools/context-bench/testset_typing_sweep.jsonl
"""

from __future__ import annotations

import json
import random
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKIP = {"testset_typing.jsonl", "testset_typing_holdout.jsonl", "testset_typing_sweep.jsonl"}


def main():
    args = sys.argv[1:]
    conf = Path(args[0])
    n = int(args[args.index("--n") + 1]) if "--n" in args else 400
    rnd = random.Random(int(args[args.index("--seed") + 1]) if "--seed" in args else 7)
    typed_for: dict[str, list[str]] = {}
    for line in conf.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        typed, *intended = line.split()
        for w in intended:
            typed_for.setdefault(w, []).append(typed)
    sentences = set()
    for f in sorted(HERE.glob("testset*.jsonl")):
        if f.name in SKIP:
            continue
        for l in f.read_text(encoding="utf-8").splitlines():
            if not l.strip():
                continue
            e = json.loads(l).get("expected", "")
            # plain typed-looking sentences only
            if 5 <= len(e.split()) <= 30 and re.fullmatch(r"[A-Za-z0-9 ,.'?!\-]+", e):
                sentences.add(e)
    sentences = sorted(sentences)
    rnd.shuffle(sentences)
    out = []
    for i, s in enumerate(sentences[:n]):
        out.append({"id": f"sc{i:03d}", "cat": "control", "input": s, "expected": s})
        words = list(re.finditer(r"[A-Za-z]+", s))
        hits = [m for m in words if m.group(0).lower() in typed_for]
        if not hits:
            continue
        m = rnd.choice(hits)
        w = m.group(0)
        t = rnd.choice(typed_for[w.lower()])
        t = t.capitalize() if w[0].isupper() else t
        out.append({"id": f"sp{i:03d}", "cat": "typo", "input": s[:m.start()] + t + s[m.end():], "expected": s,
                    "slip": f"{w.lower()}>{t.lower()}"})
    (HERE / "testset_typing_sweep.jsonl").write_text("\n".join(json.dumps(o) for o in out) + "\n", encoding="utf-8")
    print(f"{sum(o['cat'] == 'control' for o in out)} controls, {sum(o['cat'] == 'typo' for o in out)} slips")


if __name__ == "__main__":
    main()

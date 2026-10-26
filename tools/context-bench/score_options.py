"""Scores context-harness results on the per-option test sets (testset_<option>.jsonl).

Items with cat "control" are negatives (must come back unchanged: any change is a false
edit); all others are positives (must come back as "expected" or one of "alts").

  python score_options.py TESTSET.jsonl RESULT.jsonl [RESULT.jsonl ...] [-v]

Prints per result file: positives fixed (recall), negatives unchanged (precision on the
negatives), wrong changes on positives, and the mean time per item.
"""

from __future__ import annotations

import json
import sys


def norm(s: str) -> str:
    return " ".join(s.split(" ")).strip()


def load(path):
    return [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]


def main():
    args = [a for a in sys.argv[1:] if a != "-v"]
    verbose = "-v" in sys.argv
    items = {j["id"]: j for j in load(args[0])}
    for path in args[1:]:
        recs = load(path)
        pos = neg = pos_ok = neg_ok = wrong = 0
        secs = 0.0
        lines = []
        for r in recs:
            it = items[r["id"]]
            out = norm(r["guarded"])
            secs += r.get("secs", 0.0)
            targets = [norm(it["expected"])] + [norm(a) for a in it.get("alts", [])]
            if it["cat"] == "control":
                neg += 1
                if out == norm(it["input"]):
                    neg_ok += 1
                else:
                    lines.append(f"  FALSE EDIT {r['id']}: {it['input']!r} -> {r['guarded']!r}")
            else:
                pos += 1
                if out in targets:
                    pos_ok += 1
                elif out != norm(it["input"]):
                    wrong += 1
                    lines.append(f"  WRONG {r['id']}: {it['input']!r} -> {r['guarded']!r} (want {it['expected']!r})")
                else:
                    lines.append(f"  MISS {r['id']}: {it['input']!r} (want {it['expected']!r})")
            if verbose:
                for t in r.get("trace", []):
                    lines.append(f"      {r['id']} {t}")
        n = len(recs)
        print(f"{path}: recall {pos_ok}/{pos}, negatives unchanged {neg_ok}/{neg}, wrong changes {wrong}, "
              f"mean {1000 * secs / max(n, 1):.0f} ms/item")
        for l in lines:
            print(l)


if __name__ == "__main__":
    main()

"""Compares the Kotlin context stage (context-harness run) with rescore.py on the same items.

  python compare.py KOTLIN.jsonl PYTHON.rescore.jsonl

Reports per item: final text equal?, same edits (word/tag/question decisions)?, and the
largest log-prob delta difference. Stdlib only.
"""

import json
import sys


def load(path):
    return {r["id"]: r for r in (json.loads(l) for l in open(path, encoding="utf-8") if l.strip())}


def main():
    kt, py = load(sys.argv[1]), load(sys.argv[2])
    same_text = same_edits = 0
    max_dd = 0.0
    rows = []
    for k, p in py.items():
        if k not in kt:
            rows.append(f"{k}: missing in Kotlin results")
            continue
        q = kt[k]
        ek = [tuple(e[:3]) for e in q["edits"]]
        ep = [tuple(e[:3]) for e in p["edits"]]
        dd = max([abs(a[3] - b[3]) for a, b in zip(q["edits"], p["edits"])] or [0.0])
        if ek == ep:
            max_dd = max(max_dd, dd)
        same_text += q["guarded"] == p["guarded"]
        same_edits += ek == ep
        if q["guarded"] != p["guarded"] or ek != ep:
            rows.append(f"{k}: MISMATCH\n  py: {p['guarded']}\n      {p['edits']}\n  kt: {q['guarded']}\n      {q['edits']}")
        if q.get("n_cand_words") != p.get("n_cand_words"):
            rows.append(f"{k}: candidate words py={p.get('n_cand_words')} kt={q.get('n_cand_words')}")
    for r in rows:
        print(r)
    n = len(py)
    print(f"{n} items: final text identical {same_text}/{n}, edit decisions identical {same_edits}/{n}, "
          f"max |delta difference| on matching edits {max_dd:.2f}")
    tp = sum(sum(r.get("phone_ms", [])) for r in py.values()) / 1000
    tk = sum(sum(r.get("phone_ms", [])) for r in kt.values()) / 1000
    print(f"LM time: python/ctxrun {tp:.1f} s, kotlin/JNI {tk:.1f} s")


if __name__ == "__main__":
    main()

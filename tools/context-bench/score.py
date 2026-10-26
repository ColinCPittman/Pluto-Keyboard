"""Scores results/<name>.<mode>.jsonl files against testset.jsonl.

Edit units are derived per input word position i:
  word unit  : the word at i changes (case-insensitive)
  punct unit : the punctuation after word i changes
For each item the expected output (or the best-matching alternative) defines the
intended units. A unit changed in the final text counts as a hit if it matches the
target, otherwise as spurious. Controls must come back unchanged.

  python score.py [glob]      (default: results/*.jsonl)
"""

from __future__ import annotations

import glob
import json
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from guard import _punct, align, guard, norm_word, tokenize  # noqa: E402


def units(inp: str, other: str) -> dict:
    """Map of unit -> value for every place where `other` differs from `inp`."""
    iw, ig = tokenize(inp)
    ow, og = tokenize(other)
    blocks = align([norm_word(w) for w in iw], [norm_word(w) for w in ow])
    u = {}
    for b in blocks:
        if b.op == "equal":
            pi, po = _punct(ig[b.i2]), _punct(og[b.j2])
            if pi != po:
                u[("p", b.i2)] = po
        elif b.op in ("replace", "delete"):
            u[("w", b.i1, b.i2)] = " ".join(norm_word(w) for w in ow[b.j1:b.j2])
            pi, po = _punct(ig[b.i2]), _punct(og[b.j2]) if b.op == "replace" else _punct(ig[b.i2])
            if pi != po:
                u[("p", b.i2)] = po
        elif b.op == "insert":
            u[("ins", b.i1)] = " ".join(ow[b.j1:b.j2])
    return u


def norm(s: str) -> str:
    return " ".join(s.split())


def score_item(item: dict, final: str) -> dict:
    targets = [item["expected"]] + item.get("alts", [])
    fu = units(item["input"], final)
    best = None
    for t in targets:
        tu = units(item["input"], t)
        hits = sum(1 for k, v in tu.items() if fu.get(k) == v)
        spurious = sum(1 for k, v in fu.items() if tu.get(k) != v)
        cand = {"exact": norm(final) == norm(t), "n_target": len(tu), "hits": hits, "spurious": spurious,
                "q_target": sum(1 for k, v in tu.items() if k[0] == "p" and "?" in v),
                "q_hits": sum(1 for k, v in tu.items() if k[0] == "p" and "?" in v and fu.get(k) == v),
                "w_target": sum(1 for k in tu if k[0] == "w"),
                "w_hits": sum(1 for k, v in tu.items() if k[0] == "w" and fu.get(k) == v)}
        key = (cand["exact"], cand["hits"] - cand["spurious"])
        if best is None or key > (best["exact"], best["hits"] - best["spurious"]):
            best = cand
    best["unchanged"] = norm(final) == norm(item["input"])
    return best


def summarize(path: str, testset: dict) -> dict:
    recs = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
    agg = defaultdict(float)
    by_cat = defaultdict(lambda: [0, 0])
    raw_rejects = defaultdict(int)
    for r in recs:
        it = testset[r["id"]]
        s = score_item(it, r["guarded"])
        sr = score_item(it, r["candidate"])
        cat = it["cat"]
        by_cat[cat][0] += s["exact"]
        by_cat[cat][1] += 1
        if cat == "control":
            agg["ctrl_unchanged"] += s["unchanged"]
            agg["ctrl_n"] += 1
            agg["ctrl_raw_unchanged"] += sr["unchanged"]
        else:
            agg["w_target"] += s["w_target"]; agg["w_hits"] += s["w_hits"]
            agg["q_target"] += s["q_target"]; agg["q_hits"] += s["q_hits"]
            agg["raw_w_hits"] += sr["w_hits"]; agg["raw_q_hits"] += sr["q_hits"]
        agg["spurious"] += s["spurious"]
        agg["raw_spurious"] += sr["spurious"]
        agg["exact"] += s["exact"]
        agg["raw_exact"] += sr["exact"]
        agg["n"] += 1
        agg["secs"] += r.get("secs", 0)
        for d in r["decisions"]:
            if not d["accepted"]:
                raw_rejects[d["op"]] += 1
    return {"agg": dict(agg), "by_cat": {k: v for k, v in by_cat.items()}, "rejects": dict(raw_rejects), "recs": recs}


def main():
    pattern = sys.argv[1] if len(sys.argv) > 1 else str(HERE / "results" / "*.jsonl")
    testset = {}
    for fn in ("testset.jsonl", "testset_extra.jsonl"):
        testset.update({j["id"]: j for j in (json.loads(l) for l in open(HERE / fn, encoding="utf-8") if l.strip())})
    rows = []
    for p in sorted(glob.glob(pattern)):
        name = Path(p).name[:-6]
        s = summarize(p, testset)
        a = s["agg"]
        if not a.get("n"):
            continue
        a.setdefault("ctrl_unchanged", 0); a.setdefault("ctrl_n", 0); a.setdefault("ctrl_raw_unchanged", 0)
        for key in ("w_target", "w_hits", "q_target", "q_hits", "raw_w_hits", "raw_q_hits"):
            a.setdefault(key, 0)
        rows.append((name,
                     f"{int(a['exact'])}/{int(a['n'])}",
                     f"{int(a['w_hits'])}/{int(a['w_target'])}",
                     f"{int(a['q_hits'])}/{int(a['q_target'])}",
                     f"{int(a['ctrl_unchanged'])}/{int(a['ctrl_n'])}",
                     f"{int(a['spurious'])}",
                     f"{int(a['raw_exact'])}",
                     f"{int(a['raw_w_hits'])}/{int(a['raw_q_hits'])}",
                     f"{int(a['raw_spurious'])}",
                     f"{int(a['ctrl_raw_unchanged'])}",
                     " ".join(f"{k}:{v}" for k, v in sorted(s["rejects"].items())),
                     f"{a['secs']:.0f}s"))
    hdr = ("run", "exact", "words", "q?", "ctrl=", "spur", "rawEx", "rawW/Q", "rawSpur", "rawCtrl=", "guard rejected", "time")
    w = [max(len(str(r[i])) for r in rows + [hdr]) for i in range(len(hdr))]
    for r in [hdr] + rows:
        print("  ".join(str(c).ljust(w[i]) for i, c in enumerate(r)))


if __name__ == "__main__":
    main()

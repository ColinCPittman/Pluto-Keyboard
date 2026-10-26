"""Re-applies the current guard to existing results/*.jsonl (no model calls needed)."""
import glob
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from bench_desktop import clean_output  # noqa: E402
from guard import apply_edits, guard, parse_edits, strip_echo  # noqa: E402
from bench_desktop import split_sentences  # noqa: E402

for p in sorted(glob.glob(str(HERE / "results" / (sys.argv[1] if len(sys.argv) > 1 else "*.jsonl")))):
    mode = Path(p).name.split(".")[-2]
    fmt = mode.replace("sent-", "")
    recs = [json.loads(l) for l in open(p, encoding="utf-8")]
    for r in recs:
        texts = split_sentences(r["input"]) if mode.startswith("sent-") else [r["input"]]
        raws = r["raw"].split("\n||\n") if mode.startswith("sent-") else [r["raw"]]
        cands, outs, decs = [], [], []
        for k, (t, raw) in enumerate(zip(texts, raws)):
            o = clean_output(raw)
            before = " ".join(texts[max(0, k - 2):k]) or None
            c = strip_echo(before, o) if fmt == "rewrite" else apply_edits(t, parse_edits(o))[0]
            g = guard(t, c)
            cands.append(c); outs.append(g.text); decs += [d.__dict__ for d in g.decisions]
        r["candidate"], r["guarded"], r["decisions"] = " ".join(cands), " ".join(outs), decs
    with open(p, "w", encoding="utf-8") as f:
        for r in recs:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

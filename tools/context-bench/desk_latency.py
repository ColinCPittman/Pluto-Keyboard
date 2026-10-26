"""Desktop CPU latency/memory runs of ctxrun (generation mode) for the prompt files made
by make_phone_prompts.py. Absolute numbers are desktop (i9-12900K) numbers; the point
is the relative effect of prefix caching, copy-speculation and the edits-only format.

  python desk_latency.py --model m.gguf --prompts DIR/<preset>.latency.rewrite.json --tag x [--threads 4]
"""

import argparse
import json
import subprocess
import time
from pathlib import Path

import psutil

HERE = Path(__file__).resolve().parent


def run(exe, model, prompts, extra, threads):
    cmd = [exe, "-m", model, "-f", prompts, "-t", str(threads), *extra]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
    ps = psutil.Process(p.pid)
    peak = 0
    out = []
    while True:
        try:
            peak = max(peak, ps.memory_info().peak_wset)
        except Exception:
            pass
        line = p.stdout.readline()
        if not line:
            break
        out.append(json.loads(line))
    p.wait()
    return out, peak


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--exe", default="D:/FUTO-models/context/build-ctxrun-win/ctxrun.exe")
    ap.add_argument("--model", required=True)
    ap.add_argument("--prompts", required=True, help="<preset>.latency.rewrite.json")
    ap.add_argument("--tag", required=True)
    ap.add_argument("--threads", type=int, default=4)
    args = ap.parse_args()
    edits = args.prompts.replace(".rewrite.json", ".edits.json")
    variants = {
        "rw_plain": (args.prompts, []),
        "rw_spec": (args.prompts, ["--spec", "16"]),
        "rw_nocache": (args.prompts, ["--spec", "16", "--no-prefix-cache"]),
        "edits": (edits, []),
    }
    (HERE / "results" / "desk").mkdir(parents=True, exist_ok=True)
    summary = {}
    for name, (pf, extra) in variants.items():
        recs, peak = run(args.exe, args.model, pf, extra, args.threads)
        hdr = recs[0]
        items = {r["id"]: r for r in recs if r["type"] == "item"}
        summary[name] = {"load_ms": round(hdr["load_ms"]), "prefix_tokens": hdr["prefix_tokens"],
                         "prefix_ms": round(hdr["prefix_ms"]), "peak_wset_mb": round(peak / 1048576),
                         "items": {k: {"total_ms": round(v["total_ms"]), "pp_ms": round(v["pp_ms"]),
                                       "tg_ms": round(v["tg_ms"]), "n_gen": v["n_gen"], "n_prompt": v["n_prompt"],
                                       "batches": v["gen_batches"]} for k, v in items.items()}}
        with open(HERE / "results" / "desk" / f"{args.tag}.{name}.jsonl", "w", encoding="utf-8") as f:
            for r in recs:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    json.dump(summary, open(HERE / "results" / "desk" / f"{args.tag}.summary.json", "w"), indent=1)
    ids = list(summary["rw_plain"]["items"])
    print(f"{args.tag}: prefix {summary['rw_plain']['prefix_tokens']} tok / {summary['rw_plain']['prefix_ms']} ms, "
          f"peak wset {summary['rw_spec']['peak_wset_mb']} MB")
    print("item".ljust(16) + "".join(n.rjust(12) for n in summary))
    for i in ids:
        print(i.ljust(16) + "".join(str(summary[n]["items"].get(i, {}).get("total_ms", "-")).rjust(12) for n in summary))


if __name__ == "__main__":
    main()

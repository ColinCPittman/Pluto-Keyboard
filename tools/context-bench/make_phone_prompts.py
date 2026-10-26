"""Builds ctxrun input files (prefix + per-item suffixes) for the phone benchmark.

  python make_phone_prompts.py --server llama-server.exe --preset qwen3-0.6b --out DIR [--set latency|full]

The chat template is applied by llama-server on the desktop; the formatted prompt is
split at the last user message so that everything before it (system prompt and
few-shot turns) is a constant prefix that ctxrun decodes once and snapshots.
"""

import argparse
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from bench_desktop import Server, load_testset, split_sentences  # noqa: E402
from prompts import messages, user_msg  # noqa: E402


def latency_items(testset):
    by = {t["id"]: t for t in testset}
    l01 = split_sentences(by["l01"]["input"])
    items = [
        ("w14_q05", by["q05"]["input"]),
        ("w31_l01a", " ".join(l01[:2])),
        ("w39_owner", by["owner"]["input"]),
        ("w67_l01", by["l01"]["input"]),
        ("w127_l02", by["l02"]["input"]),
        ("w150_l03", by["l03"]["input"]),
    ]
    out = [(i, t, None) for i, t in items]
    # per-sentence mode takes each finalized sentence with up to two previous sentences as context
    for src in ("owner", "l01"):
        sents = split_sentences(by[src]["input"])
        for k, s in enumerate(sents):
            before = " ".join(sents[max(0, k - 2):k]) or None
            out.append((f"sent_{src}_{k}", s, before))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", required=True)
    ap.add_argument("--preset", required=True)
    ap.add_argument("--gguf-dir", default="D:/FUTO-models/context/gguf")
    ap.add_argument("--out", required=True)
    ap.add_argument("--set", default="latency", choices=["latency", "full"])
    ap.add_argument("--formats", default="rewrite,edits")
    ap.add_argument("--port", type=int, default=8092)
    args = ap.parse_args()
    pr = json.load(open(HERE / "models.json"))[args.preset]
    testset = load_testset()
    if args.set == "latency":
        items = latency_items(testset)
    else:
        items = [(t["id"], t["input"], None) for t in testset]
    os.makedirs(args.out, exist_ok=True)
    srv = Server(args.server, os.path.join(args.gguf_dir, pr["file"]), args.port)
    try:
        for fmt in args.formats.split(","):
            prefix = None
            recs = []
            for iid, text, before in items:
                p = srv.template(messages(fmt, text, before, pr.get("system_mode", "system")), pr.get("kwargs", {}))
                u = user_msg(text, before)
                k = p.rfind(u)
                assert k > 0, iid
                pre, suf = p[:k], p[k:]
                if prefix is None:
                    prefix = pre
                assert pre == prefix, f"prefix differs for {iid}"
                n_words = len(text.split())
                rec = {"id": iid, "suffix": suf, "max_tokens": (2 * n_words + 24) if fmt == "rewrite" else 64,
                       "text": text}
                if before:
                    rec["before"] = before
                if fmt == "rewrite":
                    rec["draft"] = text
                recs.append(rec)
            path = Path(args.out) / f"{args.preset}.{args.set}.{fmt}.json"
            path.write_text(json.dumps({"prefix": prefix, "items": recs}, ensure_ascii=False), encoding="utf-8")
            print("wrote", path)
    finally:
        srv.close()


if __name__ == "__main__":
    main()

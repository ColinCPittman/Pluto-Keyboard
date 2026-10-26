"""Desktop quality benchmark: runs every test item through a model via llama-server.

  python bench_desktop.py --server path/to/llama-server.exe --model m.gguf --name qwen3-0.6b \
      [--kwargs '{"enable_thinking": false}'] [--modes rewrite,edits,sent-rewrite,sent-edits]

Writes results/<name>.<mode>.jsonl with raw model output, guarded text and guard decisions.
Prompts are formatted with the model's own chat template (/apply-template) and sent
as raw completions with greedy decoding, exactly what the phone runner does.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

import requests

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from guard import apply_edits, guard, parse_edits, strip_echo  # noqa: E402
import prompts  # noqa: E402
from prompts import messages  # noqa: E402


def load_testset(path=HERE / "testset.jsonl"):
    return [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]


SENT_RE = re.compile(r"(?<=[.?!])\s+(?=[A-Z0-9\"'])")


def split_sentences(text: str) -> list[str]:
    return [s for s in SENT_RE.split(text.strip()) if s]


def clean_output(raw: str) -> str:
    raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.S)
    raw = raw.replace("<think>", "").replace("</think>", "")
    raw = raw.strip()
    if raw.startswith("Text:"):
        raw = raw[5:].strip()
    # some models wrap the answer in quotes
    if len(raw) > 2 and raw[0] == raw[-1] and raw[0] in "\"“”":
        raw = raw[1:-1]
    return raw.strip()


class Server:
    def __init__(self, exe, model, port, extra=()):
        self.url = f"http://127.0.0.1:{port}"
        args = [exe, "-m", model, "--port", str(port), "-ngl", "99", "-c", "4096", "-np", "1",
                "--jinja", "--no-webui", *extra]
        self.proc = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=open(HERE / "results" / "server.log", "w"))
        for _ in range(240):
            try:
                if requests.get(self.url + "/health", timeout=1).status_code == 200:
                    return
            except Exception:
                pass
            time.sleep(0.5)
        raise RuntimeError("server did not start")

    def template(self, msgs, kwargs):
        r = requests.post(self.url + "/apply-template",
                          json={"messages": msgs, "chat_template_kwargs": kwargs, "add_generation_prompt": True})
        r.raise_for_status()
        return r.json()["prompt"]

    def complete(self, prompt, n_predict):
        r = requests.post(self.url + "/completion", json={
            "prompt": prompt, "n_predict": n_predict, "temperature": 0, "top_k": 1,
            "cache_prompt": True, "samplers": ["top_k"],
        })
        r.raise_for_status()
        j = r.json()
        return j["content"], j.get("timings", {}), j.get("tokens_evaluated", 0)

    def close(self):
        self.proc.terminate()
        try:
            self.proc.wait(10)
        except Exception:
            self.proc.kill()


def run_one(srv, fmt, text, before, kwargs, system_mode):
    p = srv.template(messages(fmt, text, before, system_mode), kwargs)
    n_words = len(text.split())
    n_predict = (2 * n_words + 24) if fmt == "rewrite" else 64
    raw, timings, n_eval = srv.complete(p, n_predict)
    out = clean_output(raw)
    if fmt == "rewrite":
        candidate = strip_echo(before, out)
        n_applied = None
    else:
        candidate, n_applied = apply_edits(text, parse_edits(out))
    g = guard(text, candidate)
    return {"raw": raw, "candidate": candidate, "guarded": g.text, "n_applied": n_applied,
            "decisions": [d.__dict__ for d in g.decisions], "timings": timings, "prompt": p}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", required=True)
    ap.add_argument("--model", help="gguf path (or use --preset)")
    ap.add_argument("--name")
    ap.add_argument("--preset", help="entry of models.json; sets --model/--name/--kwargs")
    ap.add_argument("--gguf-dir", default="D:/FUTO-models/context/gguf")
    ap.add_argument("--port", type=int, default=8091)
    ap.add_argument("--kwargs", default="{}")
    ap.add_argument("--system-mode", default="system", choices=["system", "user"])
    ap.add_argument("--modes", default="rewrite,edits,sent-rewrite,sent-edits")
    ap.add_argument("--only", default="")
    ap.add_argument("--testset", default=str(HERE / "testset.jsonl"))
    ap.add_argument("--tag", default="")
    ap.add_argument("--prompt", default=prompts.VERSION)
    args = ap.parse_args()
    kwargs = json.loads(args.kwargs)
    prompts.VERSION = args.prompt
    if args.preset:
        pr = json.load(open(HERE / "models.json"))[args.preset]
        args.model = args.model or os.path.join(args.gguf_dir, pr["file"])
        args.name = args.name or args.preset
        kwargs = pr.get("kwargs", {}) | kwargs
        args.system_mode = pr.get("system_mode", args.system_mode)
    (HERE / "results").mkdir(exist_ok=True)
    items = load_testset(args.testset)
    if args.only:
        items = [it for it in items if it["id"] in args.only.split(",")]
    srv = Server(args.server, args.model, args.port)
    try:
        for mode in args.modes.split(","):
            fmt = mode.replace("sent-", "")
            out_path = HERE / "results" / f"{args.name}{args.tag}.{mode}.jsonl"
            with open(out_path, "w", encoding="utf-8") as f:
                for it in items:
                    t0 = time.time()
                    if mode.startswith("sent-"):
                        sents = split_sentences(it["input"])
                        parts = []
                        for k, s in enumerate(sents):
                            before = " ".join(sents[max(0, k - 2):k]) or None
                            parts.append(run_one(srv, fmt, s, before, kwargs, args.system_mode))
                        rec = {"id": it["id"], "cat": it["cat"], "input": it["input"],
                               "raw": "\n||\n".join(p["raw"] for p in parts),
                               "candidate": " ".join(p["candidate"] for p in parts),
                               "guarded": " ".join(p["guarded"] for p in parts),
                               "decisions": [d for p in parts for d in p["decisions"]],
                               "n_calls": len(parts)}
                    else:
                        r = run_one(srv, fmt, it["input"], None, kwargs, args.system_mode)
                        rec = {"id": it["id"], "cat": it["cat"], "input": it["input"], **r}
                        prompt = rec.pop("prompt")
                        if it is items[0]:
                            (HERE / "results" / f"{args.name}{args.tag}.{mode}.prompt.txt").write_text(prompt, encoding="utf-8")
                    rec["secs"] = time.time() - t0
                    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            print(f"wrote {out_path}", flush=True)
    finally:
        srv.close()


if __name__ == "__main__":
    main()

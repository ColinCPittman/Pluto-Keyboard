"""Runs context-harness on several test sets in parallel (chunks of items), then scores them.

  python -X utf8 prun.py TAG SET [SET ...] [--options all] [--jobs 10] [--chunk 60]

SET is a path to a .jsonl file (relative to tools/context-bench or absolute). Results go to
D:/FUTO-models/context/harness/stress/TAG/<set name>.jsonl; the summary of score_options.py
is printed per set (with -q only the summary line and FALSE EDIT / WRONG lines).
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BENCH = Path(__file__).resolve().parent.parent
HARNESS = BENCH.parent / "context-harness/build/install/context-harness/bin/context-harness.bat"
M = Path("D:/FUTO-models/context")
OUT = M / "harness/stress"


def main():
    args = sys.argv[1:]
    opts = "all"
    jobs = 10
    chunk = 60
    quiet = False
    rest = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--options":
            opts = args[i + 1]; i += 2; continue
        if a == "--jobs":
            jobs = int(args[i + 1]); i += 2; continue
        if a == "--chunk":
            chunk = int(args[i + 1]); i += 2; continue
        if a == "-q":
            quiet = True; i += 1; continue
        rest.append(a); i += 1
    tag, sets = rest[0], rest[1:]
    outdir = OUT / tag
    tmp = outdir / "tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    tasks = []
    plan = []
    for s in sets:
        p = Path(s)
        if not p.is_absolute():
            p = BENCH / s
        if p.suffix != ".jsonl":
            p = p.with_suffix(".jsonl")
        lines = [l for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]
        name = p.stem if p.parent == BENCH else f"{p.parent.name}_{p.stem}"
        parts = []
        for k in range(0, len(lines), chunk):
            cf = tmp / f"{name}.{k // chunk:03d}.in.jsonl"
            cf.write_text("\n".join(lines[k:k + chunk]) + "\n", encoding="utf-8")
            of = tmp / f"{name}.{k // chunk:03d}.out.jsonl"
            parts.append(of)
            tasks.append((cf, of))
        plan.append((p, name, parts))
    env = dict(os.environ)
    env["JAVA_HOME"] = "D:/FUTO-models/jdk-24-harness"

    def run(t):
        cf, of = t
        cmd = [str(HARNESS), "run", "--lib", str(M / "build-ctxlm-win/libfutoctxlm.dll"),
               "--model", str(M / "gguf/Qwen_Qwen3-0.6B-Q4_0.gguf"),
               "--lexicon", str(M / "lexicon/v2/soundalike-en.lex"),
               "--testset", str(cf), "--out", str(of), "--threads", "2", "--no-repack", "--fresh",
               "--options", opts]
        r = subprocess.run(cmd, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if r.returncode != 0:
            print(f"FAILED {cf}: {r.stderr[-2000:]}", file=sys.stderr)

    with ThreadPoolExecutor(jobs) as ex:
        list(ex.map(run, tasks))
    for p, name, parts in plan:
        res = outdir / f"{name}.jsonl"
        with open(res, "w", encoding="utf-8") as w:
            for of in parts:
                if of.exists():
                    w.write(of.read_text(encoding="utf-8"))
        r = subprocess.run([sys.executable, "-X", "utf8", str(BENCH / "score_options.py"), str(p), str(res)],
                           capture_output=True, text=True, encoding="utf-8")
        out = r.stdout + r.stderr
        if quiet:
            out = "\n".join(l for l in out.splitlines() if not l.startswith("  MISS"))
        print(f"== {name}")
        print(out.rstrip())


if __name__ == "__main__":
    main()

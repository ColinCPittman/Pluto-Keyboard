"""Alternative design: the LM as a *judge* (rescoring) instead of a generator.

No instruction following, no generation. The transcript is scored once by a small
LM (one prefill pass, per-token log-probs). Words the LM finds surprising get
sound-alike candidates from a pronunciation lexicon (same phoneme count, at most one
phoneme different); each candidate is scored by re-decoding only the tokens after the
change (KV prefix reuse), and accepted if it beats the original by a margin.
Question marks: the same prefill pass gives log P("?") vs log P(".") at each sentence
end (and vs "," after tag words like "right"), so questions cost nothing extra.

  python rescore.py --exe ctxrun.exe --model m.gguf --name tag [--phone] [thresholds]

Writes results/<name>.rescore.jsonl in the same format as bench_desktop.py
(so score.py works on it), plus per-item timings.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from functools import lru_cache
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from guard import FUNCTION_LOCK, _is_inflection, guard, norm_word, sounds_alike, tokenize  # noqa: E402
from bench_desktop import load_testset, split_sentences  # noqa: E402

LM_PREFIX = "Transcript of a casual voice message:\n"
TAG_WORDS = {"right", "okay", "ok", "yeah", "correct", "huh", "no", "yes", "really"}


# ----------------------------------------------------------------------------
# Lexicon of sound-alike candidates

class Lexicon:
    def __init__(self, n_words=60000, min_zipf=3.0):
        import pronouncing
        from wordfreq import top_n_list, zipf_frequency
        self.zipf = zipf_frequency
        pronouncing.init_cmu()
        self.pron = {}
        for w, p in pronouncing.pronunciations:
            self.pron.setdefault(w.lower(), []).append(tuple(re.sub(r"\d", "", x) for x in p.split()))
        self.by_len: dict[int, list[tuple[str, tuple]]] = {}
        vocab = set(top_n_list("en", n_words))
        vocab |= {"they're", "you're", "we're", "it's", "who's", "there's", "let's", "i'll", "aisle", "isle"}
        for w in vocab:
            if not w.isalpha() and "'" not in w:
                continue
            if zipf_frequency(w, "en") < min_zipf:
                continue
            for p in self.pron.get(w, []):
                self.by_len.setdefault(len(p), []).append((w, p))

    @lru_cache(maxsize=None)
    def candidates(self, word: str, k: int = 6) -> tuple[str, ...]:
        w = norm_word(word)
        if w in FUNCTION_LOCK or len(w) < 2 or any(c.isdigit() for c in w):
            return ()
        out = {}
        for p in self.pron.get(w, []):
            for c, cp in self.by_len.get(len(p), []):
                if c == w or c in FUNCTION_LOCK:
                    continue
                diff = sum(1 for a, b in zip(p, cp) if a != b)
                if diff > 1:
                    continue
                if _is_inflection(w, c) or _is_inflection(c, w):
                    continue
                if not sounds_alike(w, c)[0]:  # keep consistent with the app-side guard
                    continue
                out[c] = max(out.get(c, -1), self.zipf(c, "en") + (1.0 if diff == 0 else 0.0))
        return tuple(sorted(out, key=lambda c: -out[c])[:k])


def match_case(src: str, dst: str) -> str:
    if src[:1].isupper():
        return dst[:1].upper() + dst[1:]
    return dst


# ----------------------------------------------------------------------------
# Scorer process (ctxrun --score), local or on the phone through adb

class Scorer:
    def __init__(self, cmd: list[str]):
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  text=True, encoding="utf-8", bufsize=1)
        while True:
            line = self.p.stderr.readline()
            if not line:
                raise RuntimeError("scorer died")
            if line.startswith("ready"):
                self.ready = line.strip()
                break

    def score(self, variants, alts=None, detail=False, probes=None):
        req = {"prefix": LM_PREFIX, "variants": variants, "alts": alts or [], "detail": detail}
        if probes:
            req["probes"] = probes
        if getattr(self, "log", None):
            self.log.write(json.dumps(req, ensure_ascii=False) + "\n")
            self.log.flush()
        self.p.stdin.write(json.dumps(req, ensure_ascii=False) + "\n")
        self.p.stdin.flush()
        return json.loads(self.p.stdout.readline())

    def close(self):
        self.p.stdin.close()
        tail = self.p.stderr.read()
        self.p.wait(30)
        return tail


# ----------------------------------------------------------------------------

def word_spans(text: str):
    words, gaps = tokenize(text)
    spans, pos = [], len(gaps[0])
    for k, w in enumerate(words):
        spans.append((pos, pos + len(w)))
        pos += len(w) + len(gaps[k + 1])
    return words, gaps, spans


def token_offsets(pieces):
    offs, pos = [], 0
    for p in pieces:
        offs.append(pos)
        pos += len(p)
    return offs


def correct(text: str, sc: Scorer, lex: Lexicon, args, focus: int = 0) -> dict:
    """focus: only words/punctuation at or after this char offset may change (streaming:
    the earlier sentences were already corrected and are context now)."""
    t0 = time.time()
    timings = []
    words, gaps, spans = word_spans(text)
    all_cands = {k: [match_case(words[k], c) for c in lex.candidates(words[k])] for k in range(len(words))}
    all_cands = {k: v for k, v in all_cands.items() if v and spans[k][0] >= focus}
    probe_keys = sorted(all_cands)
    r = sc.score([text], alts=["?", ".", ","], detail=True,
                 probes=[[spans[k][0], all_cands[k]] for k in probe_keys])
    timings.append(r["ms"])
    v = r["variants"][0]
    pieces, lps, alts = v["pieces"], v["lps"], v["alts"]
    offs = token_offsets(pieces)
    # the decoded text may have a leading space difference, so align by stripping
    joined = "".join(pieces)
    shift = joined.find(text[:20]) if not joined.startswith(text[:20]) else 0
    shift = max(0, shift)

    # word surprisal = sum of log-probs of tokens starting inside the word
    surpr = [0.0] * len(words)
    for i, o in enumerate(offs):
        c = o - shift
        # a token's piece may start with a space belonging to the gap
        lead = len(pieces[i]) - len(pieces[i].lstrip())
        c += lead
        for k, (a, b) in enumerate(spans):
            if a <= c < b:
                surpr[k] += lps[i]
                break

    edits = []  # (start, end, replacement, kind, delta)
    # --- words
    # first-token probe that drops candidates the LM already finds much less likely than the
    # original word at that position (left context only, since right context comes later)
    kept = {}
    for k, pr in zip(probe_keys, v.get("probes", [])):
        if not pr:
            continue
        orig = pr[-1]
        ok = [c for c, lp in zip(all_cands[k], pr[:-1]) if lp >= orig - args.probe_margin]
        if ok:
            kept[k] = ok[: args.k]
    cand_words = [k for k in kept if surpr[k] < args.surprise]
    cand_words = sorted(cand_words, key=lambda k: surpr[k])[: args.max_words]
    for k in cand_words:
        a, b = spans[k]
        # window runs through the end of the word's sentence plus a few following words
        m = re.search(r"[.?!](\s|$)", text[b:])
        end = b + (m.end() if m else len(text) - b)
        tail_words = re.match(r"(\S+\s+){0,%d}" % args.tail_words, text[end:])
        end += tail_words.end() if tail_words else 0
        base = text[:end]
        cands = kept[k]
        variants = [base] + [text[:a] + c + text[b:end] for c in cands]
        rr = sc.score(variants)
        timings.append(rr["ms"])
        lp0 = rr["variants"][0]["lp"]
        best = max(range(1, len(variants)), key=lambda i: rr["variants"][i]["lp"])
        delta = rr["variants"][best]["lp"] - lp0
        if delta > args.word_margin:
            edits.append((a, b, cands[best - 1], "word", delta))

    # --- tag questions ("..., right, and" -> "right? And") compare P("?") vs P(",") after tag words
    q_edits = []
    for i, piece in enumerate(pieces):
        if piece.strip() != ",":
            continue
        c = offs[i] - shift + (len(piece) - len(piece.lstrip()))
        if c <= focus or c >= len(text) or text[c] != ",":
            continue
        prev = re.findall(r"[A-Za-z']+", text[:c])
        if prev and prev[-1].lower() in TAG_WORDS:
            delta = alts[i][0] - alts[i][2]
            if delta > args.tag_margin:
                edits.append((c, c + 1, "?", "tag", delta))

    def apply(t, eds):
        for a, b, rep, kind, delta in sorted(eds, key=lambda e: -e[0]):
            t = t[:a] + rep + t[b:]
        return re.sub(r"[?] ([a-z])", lambda m: "? " + m.group(1).upper(), t)

    # --- sentence-final questions compare P("?") vs P(".") at each sentence end. If words or
    # tags were changed, re-score the edited text first (one more prefill pass) so the
    # decision sees the corrected sentence ("... right? And bowls and spoons and things?").
    text2 = apply(text, edits)
    if text2 != text:
        r2 = sc.score([text2], alts=["?", ".", ","], detail=True)
        timings.append(r2["ms"])
        v2 = r2["variants"][0]
        pieces, alts = v2["pieces"], v2["alts"]
        offs = token_offsets(pieces)
        joined = "".join(pieces)
        shift = 0 if joined.startswith(text2[:20]) else max(0, joined.find(text2[:20]))
    for i, piece in enumerate(pieces):
        if piece.strip() != ".":
            continue
        c = offs[i] - shift + (len(piece) - len(piece.lstrip()))
        if c <= focus or c >= len(text2) or text2[c] != ".":
            continue
        delta = alts[i][0] - alts[i][1]
        # follow-on fragment after a question ("right? And bowls and spoons and things.")
        sent_start = max(text2.rfind(". ", 0, c), text2.rfind("? ", 0, c), text2.rfind("! ", 0, c))
        follow = sent_start >= 0 and text2[sent_start] == "?" and             re.match(r"(And|Or|Plus) ", text2[sent_start + 2:]) is not None
        if delta > (args.follow_margin if follow else args.q_margin):
            q_edits.append((c, c + 1, "?", "q", delta))
    out = apply(text2, q_edits)
    edits_report = [(text[a:b], rep, kind, round(d, 2)) for a, b, rep, kind, d in edits] +                    [(text2[a:b], rep, kind, round(d, 2)) for a, b, rep, kind, d in q_edits]
    g = guard(text, out)
    return {"candidate": out, "guarded": g.text, "decisions": [d.__dict__ for d in g.decisions],
            "edits": edits_report,
            "n_cand_words": len(cand_words), "phone_ms": timings, "secs": time.time() - t0,
            "surprisal": [(w, round(s, 1)) for w, s in zip(words, surpr) if s < args.surprise]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--exe", default="D:/FUTO-models/context/build-ctxrun-win/ctxrun.exe")
    ap.add_argument("--model", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--phone", action="store_true", help="run ctxrun on the phone via adb (model already pushed)")
    ap.add_argument("--serial", default="5003AF2010009554")
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--surprise", type=float, default=-4.0)
    ap.add_argument("--max-words", type=int, default=8)
    ap.add_argument("--k", type=int, default=6, help="candidates per word")
    ap.add_argument("--probe-margin", type=float, default=3.0)
    ap.add_argument("--tail-words", type=int, default=6)
    ap.add_argument("--word-margin", type=float, default=2.0)
    ap.add_argument("--q-margin", type=float, default=0.0)
    ap.add_argument("--tag-margin", type=float, default=0.0)
    ap.add_argument("--follow-margin", type=float, default=-3.0)
    ap.add_argument("--only", default="")
    ap.add_argument("--testset", default=str(HERE / "testset.jsonl"))
    ap.add_argument("--stream", action="store_true", help="per-sentence incremental mode")
    ap.add_argument("--log", default="", help="write scorer requests to this file")
    ap.add_argument("--items", default="", help="JSON file with extra/override items")
    args = ap.parse_args()
    lex = Lexicon()
    if args.phone:
        cmd = ["adb", "-s", args.serial, "shell",
               f"cd /data/local/tmp/ctxbench && ./ctxrun --score -m {args.model} -t 2 --cpus 6,7"]
    else:
        cmd = [args.exe, "--score", "-m", args.model, "-t", str(args.threads)]
    sc = Scorer(cmd)
    if args.log:
        sc.log = open(args.log, "w", encoding="utf-8")
    items = load_testset(args.testset)
    if args.only:
        items = [it for it in items if it["id"] in args.only.split(",")]
    (HERE / "results").mkdir(exist_ok=True)
    out_path = HERE / "results" / f"{args.name}.{'rescore-stream' if args.stream else 'rescore'}.jsonl"
    with open(out_path, "w", encoding="utf-8") as f:
        for it in items:
            if args.stream:
                # per-sentence, so each finalized sentence is corrected as it arrives, with the
                # already-corrected earlier sentences as context, and only the last step runs after "Done"
                done, steps, edits, decs, ncw = "", [], [], [], 0
                for snt in split_sentences(it["input"]):
                    t = (done + " " + snt) if done else snt
                    rr = correct(t, sc, lex, args, focus=len(done) + 1 if done else 0)
                    done = rr["guarded"]
                    steps.append(sum(rr["phone_ms"]))
                    edits += rr["edits"]; decs += rr["decisions"]; ncw += rr["n_cand_words"]
                r = {"candidate": done, "guarded": done, "decisions": decs, "edits": edits,
                     "n_cand_words": ncw, "phone_ms": steps, "step_ms": steps, "after_done_ms": steps[-1]}
            else:
                r = correct(it["input"], sc, lex, args)
            rec = {"id": it["id"], "cat": it["cat"], "input": it["input"], "raw": json.dumps(r["edits"]), **r}
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    tail = sc.close()
    print(sc.ready, tail.strip()[-200:])
    print("wrote", out_path)


if __name__ == "__main__":
    main()

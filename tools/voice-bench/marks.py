"""
Voice model alone on the spoken bench: word errors and punctuation marks (see README.md).

  python -I marks.py alone <work> <model>...   writes <work>/testset-<model>-alone.jsonl and out-<model>-alone.jsonl
                                                (the model's text unchanged), so 'bench.py score <work> <model>-alone'
                                                scores the model alone
  python -I marks.py report <work> <model>...  word error rate, end marks and commas per model

WER% compares with what Pluto should type (so self-corrections and spoken commands count as errors
until Smart corrections handle them), "heard" with what was said (numbers with what Pluto should type).
End marks, commas and question marks count only lines whose expected text ends in ".", "?" or "!"
(plain sentences, quotes, long dictation and a few commands; the other lines leave punctuation out),
like the end mark columns of 'bench.py score'. Commas, question marks and inner stops (sentence ends
before the last word) leave out the quotes, whose marks follow Smart corrections' quote format
(She said "..."), and count only on the words the model got right (aligned with the expected text),
so a misheard word doesn't count as a missed comma.
"wait ms" is what runs after the user stops: Moonshine's finalize plus the punctuation stage.
"""
import difflib, json, os, sys
from collections import Counter

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bench import end_mark, need_file, norm  # noqa: E402

QUOTES = "\"'“”‘’()"


def load(work, name):
    path = os.path.join(work, f"testset-{name}.jsonl")
    need_file(path, "testset")
    with open(path, encoding="utf8") as f:
        return [json.loads(l) for l in f if l.strip()]


def alone(work, name):
    rows = load(work, name)
    with open(os.path.join(work, f"testset-{name}-alone.jsonl"), "w", encoding="utf8") as f:
        for d in rows:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    with open(os.path.join(work, f"out-{name}-alone.jsonl"), "w", encoding="utf8") as f:
        for d in rows:
            f.write(json.dumps({"id": d["id"], "input": d["input"], "guarded": d["input"]}, ensure_ascii=False) + "\n")
    print(len(rows), "lines ->", os.path.join(work, f"out-{name}-alone.jsonl"))


def marked_words(s):
    """(word, mark after it) per whitespace token: the word lowercased without edge punctuation,
    the mark one of , . ? ! or ''."""
    out = []
    for t in s.split():
        core = t.strip(QUOTES)
        mark = core[-1] if core and core[-1] in ",.?!;:" else ""
        w = core.rstrip(",.?!;:").strip(QUOTES).lower()
        if w:
            out.append((w, mark))
        elif out and mark:
            out[-1] = (out[-1][0], mark)
    return out


def aligned_marks(hyp, exp):
    """(mark in hyp, mark in exp, is the last word) for each word hyp has right (aligned with exp),
    so a misheard word doesn't count as a missed mark (also tools/voice-bench/ablate.py)."""
    hw, ew = marked_words(hyp), marked_words(exp)
    sm = difflib.SequenceMatcher(a=[w for w, _ in hw], b=[w for w, _ in ew], autojunk=False)
    out = []
    for blk in sm.get_matching_blocks():
        for k in range(blk.size):
            out.append((hw[blk.a + k][1], ew[blk.b + k][1], blk.b + k == len(ew) - 1))
    return out


def edits(a, b):
    """Word-level Levenshtein distance."""
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, y in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y))
        prev = cur
    return prev[-1]


def report(work, names):
    sys.stdout.reconfigure(encoding="utf-8")
    print(f"{'model':28s} {'lines':>5s} {'right':>5s} {'WER%':>5s} {'heard':>5s}   {'end mark':>11s} {'none':>4s}"
          f"   {'commas: hit':>11s} {'missed':>6s} {'extra':>5s} {'P%':>4s} {'R%':>4s}   {'? hit/of':>8s} {'extra':>5s}"
          f"   {'inner stops: hit':>16s} {'missed':>6s} {'extra':>5s}   wait ms")
    for name in names:
        rows = load(work, name)
        c = Counter()
        ms = []
        for d in rows:
            cat, hyp, exp = d["cat"], d["input"], d["expected"]
            h, e = norm(hyp, cat), norm(exp, cat)
            c["lines"] += 1
            c["right"] += h == e
            c["err"] += edits(h, e)
            c["words"] += len(e)
            # what was said, except numbers, which are said as words and written as digits
            s = e if cat == "number" else norm(d["say"].replace("|", " "), cat)
            c["heard_err"] += edits(h, s)
            c["heard_words"] += len(s)
            t = d.get("finalize_ms", 0) + d.get("punct_ms", 0)
            ms.append(t)
            if not end_mark(exp):
                continue  # the other lines' expected text leaves punctuation out
            c["marked"] += 1
            c["end_ok"] += end_mark(hyp) == end_mark(exp)
            c["end_none"] += end_mark(hyp) == ""
            if cat == "quote":
                continue  # the marks around a quote are Smart corrections' quote format (said "...")
            for hm, em, last in aligned_marks(hyp, exp):
                if not last:  # the end mark is counted above
                    c["comma_hit"] += hm == "," and em == ","
                    c["comma_miss"] += em == "," and hm != ","
                    c["comma_extra"] += hm == "," and em != ","
                    hb, eb = hm in (".", "?", "!"), em in (".", "?", "!")
                    c["stop_hit"] += hb and eb
                    c["stop_miss"] += eb and not hb
                    c["stop_extra"] += hb and not eb
                c["q_of"] += em == "?"
                c["q_hit"] += em == "?" and hm == "?"
                c["q_extra"] += hm == "?" and em != "?"
        p = 100 * c["comma_hit"] / max(1, c["comma_hit"] + c["comma_extra"])
        r = 100 * c["comma_hit"] / max(1, c["comma_hit"] + c["comma_miss"])
        print(f"{name:28s} {c['lines']:5d} {c['right']:5d} {100 * c['err'] / c['words']:5.1f} {100 * c['heard_err'] / c['heard_words']:5.1f}   "
              f"{c['end_ok']:4d} / {c['marked']:4d} {c['end_none']:4d}   {c['comma_hit']:11d} {c['comma_miss']:6d} {c['comma_extra']:5d} "
              f"{p:4.0f} {r:4.0f}   {c['q_hit']:3d}/{c['q_of']:<4d} {c['q_extra']:5d}"
              f"   {c['stop_hit']:16d} {c['stop_miss']:6d} {c['stop_extra']:5d}   {sum(ms) / len(ms):.0f}")


USAGE = __doc__.strip().split("\n", 2)[2]

if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["alone"] and len(a) >= 3:
        for n in a[2:]:
            alone(a[1], n)
    elif a[:1] == ["report"] and len(a) >= 3:
        report(a[1], a[2:])
    else:
        sys.exit("usage:\n" + USAGE)

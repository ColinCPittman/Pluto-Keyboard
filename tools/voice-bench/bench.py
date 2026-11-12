"""
Voice model + Smart corrections bench on synthetic speech (see README.md).

  python -I bench.py items  <work>                 writes <work>/items.json (set 1: each line, two voices)
  python -I bench.py items2 <work>                 writes <work>/items.json (set 2: each line, four voices)
  (powershell) tts.ps1 <work>/items.json <work>/wav
  python -I bench.py nemotron|parakeet <work> <cli> <gguf> [--threads N]
                                                   runs the model like the phone, writes <work>/testset-<model>.jsonl
  (context-harness run ... --testset <work>/testset-<model>.jsonl --out <work>/out-<model>.jsonl)
  python -I bench.py score <work> <model> [--alone]
                                                   model alone vs with Smart corrections, per category
                                                   (--alone: the model only, no context-harness output needed)
"""
import json, os, re, string, subprocess, sys, time
from collections import Counter

sys.dont_write_bytecode = True  # no __pycache__ next to sentences.py
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sentences import ITEMS, ITEMS2, PREFIX2, STRICT2  # noqa: E402

VOICES = ["Microsoft David Desktop", "Microsoft Zira Desktop"]
# set 2: (id letter, voice, speaking rate or None). "Microsoft Mark" is a OneCore voice that comes with
# Windows; "Microsoft David" / "Microsoft Zira" (OneCore) sound the same as the Desktop ones.
VOICES2 = [("d", "Microsoft David Desktop", None), ("z", "Microsoft Zira Desktop", None),
           ("m", "Microsoft Mark", None), ("f", "Microsoft Zira Desktop", "115%")]
THREADS = 4
GAP_MS = 500  # PauseSegments.DEFAULT_GAP_MS


def need_file(path, what):
    if not os.path.isfile(path):
        sys.exit(f"{what} not found: {path}")


def items(work):
    os.makedirs(work, exist_ok=True)
    out = []
    for i, (cat, say, expected) in enumerate(ITEMS):
        for v, voice in enumerate(VOICES):
            out.append({"id": f"{cat[:3]}{i:03d}{'dz'[v]}", "cat": cat, "say": say, "expected": expected, "voice": voice})
    with open(os.path.join(work, "items.json"), "w", encoding="utf8") as f:
        json.dump(out, f, indent=0)
    print(len(out), "items")


def items2(work):
    """Set 2 (sentences.py ITEMS2): ids are PREFIX2[category] + line number + voice letter."""
    os.makedirs(work, exist_ok=True)
    out = []
    for i, it in enumerate(ITEMS2):
        cat, say, expected = it[:3]
        opts = it[3] if len(it) > 3 else {}
        if cat not in PREFIX2:
            sys.exit(f"no id prefix for category {cat!r} (sentences.py PREFIX2)")
        extra = {k: opts[k] for k in ("alt", "personal") if k in opts}
        strict = opts.get("strict", STRICT2.get(cat))
        if strict:
            extra["strict"] = strict
        for tag, voice, rate in VOICES2:
            d = {"id": f"{PREFIX2[cat]}{i:03d}{tag}", "cat": cat, "say": say, "expected": expected, "voice": voice}
            if rate:
                d["rate"] = rate
            out.append({**d, **extra})
    ids = [d["id"] for d in out]
    assert len(ids) == len(set(ids)), "duplicate ids"
    with open(os.path.join(work, "items.json"), "w", encoding="utf8") as f:
        json.dump(out, f, indent=0, ensure_ascii=False)
    print(len(out), "items")


TOKEN = re.compile(r"^\s*\[\s*([\d.]+)\s*->\s*([\d.]+)\]\s*p=[\d.]+ (.*)$")


def words_of(tokens):
    """transcribe_jni.cpp result_words(): a piece starting with a space starts a word, punctuation
    joins the word before without moving its end. Nemotron sends the space before a number as a
    piece of its own (" ", "3", "0", "0"), so a space-only piece makes the next piece start a word
    (without that, "We drove 300" became the word "drove300")."""
    out, space = [], False
    for t0, t1, piece in tokens:
        if not piece:
            continue
        starts = space or piece.startswith(" ") or not out
        piece = piece.lstrip(" ")
        space = not piece
        if not piece:
            continue
        if starts:
            out.append([piece, t0, t1])
        else:
            out[-1][0] += piece
            if not all(c in string.punctuation for c in piece):
                out[-1][2] = t1
    return out


def word_ends(words, tokens):
    """transcribe_jni.cpp result_words() for a model with word rows (Parakeet): a word ends with its
    last token that isn't punctuation. Parakeet emits a mark once speech resumes ("you" 0.72-0.96,
    "?" 1.44-1.60), so the row "you?" would end after the pause and hide it. Tokens are matched to
    words in order by time, as the JNI matches them by word index."""
    k = 0
    for w in words:
        end = None
        while k < len(tokens) and tokens[k][0] < w[2] - 1e-6:
            t0, t1, piece = tokens[k]
            piece = piece.strip(" ")
            if t0 >= w[1] - 1e-6 and piece and not all(c in string.punctuation for c in piece):
                end = t1
            k += 1
        if end is not None and w[1] <= end <= w[2]:
            w[2] = end
    return words


def segments_of(text, words):
    """PauseSegments.split()."""
    if not words:
        return [text.strip()] if text.strip() else []
    segs, cur = [], []
    for i, (w, t0, t1) in enumerate(words):
        if cur and t0 - words[i - 1][2] >= GAP_MS / 1000:
            segs.append(" ".join(cur))
            cur = []
        cur.append(w)
    if cur:
        segs.append(" ".join(cur))
    return segs


WORD = re.compile(r"^\s*\[\s*([\d.]+)\s*->\s*([\d.]+)\]\s+(\S.*)$")


def run_model(work, cli, gguf, streaming, name, threads=THREADS):
    """A streaming model (Nemotron) is fed like the phone and its word pieces grouped; a model that
    runs on the whole recording (Parakeet) reports words itself. Stops at the first clip the CLI
    fails on, since an empty transcript would silently count as a model mistake. Each clip is also
    written to testset-<model>.jsonl.part as it is done, and a run that was stopped carries on from there."""
    need_file(cli, "transcribe-cli")
    need_file(gguf, "model")
    need_file(os.path.join(work, "items.json"), "items.json (run 'bench.py items' first)")
    with open(os.path.join(work, "items.json"), encoding="utf8") as f:
        its = json.load(f)
    part = os.path.join(work, f"testset-{name}.jsonl.part")
    done = {}
    if os.path.isfile(part):
        with open(part, encoding="utf8") as f:
            for line in f:
                try:
                    d = json.loads(line)
                except ValueError:
                    continue  # a line cut off when the run was stopped
                done[d["id"]] = d
        print(f"carrying on: {len(done)} clips already in {part}")
    pf = open(part, "a", encoding="utf8")
    out, no_timing, t_start, n_run = [], 0, time.time(), 0
    for n, it in enumerate(its):
        if it["id"] in done:
            out.append(done[it["id"]])
            continue
        wav = os.path.join(work, "wav", it["id"] + ".wav")
        need_file(wav, "recording (run tts.ps1 first)")
        args = [cli, "-q", "--threads", str(threads), "-m", gguf] + (["--stream-chunk-ms", "160"] if streaming else ["--timestamps", "token"]) + [wav]
        r = subprocess.run(args, capture_output=True, text=True, encoding="utf8", errors="replace")
        if r.returncode != 0:
            sys.exit(f"{it['id']}: transcribe-cli exited with {r.returncode}\n{r.stderr.strip()[-2000:]}")
        text, have_text, tokens, words, in_words = "", False, [], [], False
        for line in r.stdout.splitlines():
            if line.startswith("text: "):
                text, have_text = line[6:].strip(), True
            if line.startswith("words:"):
                in_words = True
                continue
            m = TOKEN.match(line)
            if m:
                tokens.append((float(m[1]), float(m[2]), m[3]))
                continue
            m = WORD.match(line)
            if m and in_words:
                words.append([m[3].strip(), float(m[1]), float(m[2])])
        if not have_text:
            sys.exit(f"{it['id']}: no 'text: ' line in transcribe-cli output (format changed?)\n{r.stdout[-2000:]}")
        timed = words_of(tokens) if streaming else word_ends(words, tokens)
        if text and not timed:
            no_timing += 1  # no pause splits for this clip
        segs = segments_of(text, timed)
        d = {"id": it["id"], "cat": it["cat"], "say": it["say"], "input": text, "segments": segs, "expected": it["expected"]}
        d.update({k: it[k] for k in ("alt", "personal", "strict") if k in it})  # set 2; the context harness reads "personal"
        out.append(d)
        pf.write(json.dumps(d, ensure_ascii=False) + "\n")
        pf.flush()
        n_run += 1
        if n % 25 == 0:
            print(n, it["id"], repr(text), f"({(time.time() - t_start) / n_run:.2f} s per clip)", flush=True)
    pf.close()
    with open(os.path.join(work, f"testset-{name}.jsonl"), "w", encoding="utf8") as f:
        for d in out:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    os.remove(part)
    if n_run:
        print(f"{n_run} clips in {time.time() - t_start:.0f} s ({(time.time() - t_start) / n_run:.2f} s per clip, {threads} threads)")
    if no_timing:
        print(f"warning: {no_timing} clips had text but no {'token' if streaming else 'word'} timestamps, so no pause splits")
    print(len(out), "clips ->", os.path.join(work, f"testset-{name}.jsonl"))


SMALL = {w: str(i) for i, w in enumerate("zero one two three four five six seven eight nine ten eleven twelve".split())}
DIGITS = {w: d for w, d in SMALL.items() if len(d) == 1}


EMOJI = re.compile("[\U0001F000-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF\uFE0F\u200D]+")


def norm(s, cat="", strict=""):
    """Words for comparison: lowercase, line breaks and quotes kept as tokens, other punctuation
    dropped (a mark between digits stays: 3:30, 45.99). Spellings that are equally right count
    the same: "one-on-one" and "one on one", "$1,200" and "$1200", ’ and ', "6 p.m." and "6pm" and "6 PM". A small
    number may be a word or digits ("a table for six"); in the number category only below ten, which
    Pluto leaves as words and a model may write either way ("six tables"). An emoji is a word of its own.
    [strict] (set 2, "+" between them) compares more: "case" keeps capitals, "end" keeps . ? ! as
    words wherever they are, "comma" keeps commas, "numbers" makes a number word and its digits differ."""
    asp = set(strict.split("+")) if strict else set()
    s = s.replace("\n", " ¶ ").replace("“", '"').replace("”", '"').replace("’", "'").replace("‘", "'")
    s = re.sub(r"\b([aApP])\.([mM])\.?(?!\w)", r"\1\2", s)
    s = re.sub(r"(?<=\d)([aApP][mM])\b", r" \1", s)
    s = EMOJI.sub(lambda m: " " + m[0].replace("\ufe0f", "") + " ", s)
    s = re.sub(r"(?<=\d),(?=\d{3}\b)", "", s)
    s = re.sub(r"(?<=[^\W\d_])-(?=[^\W\d_])", " ", s)
    keep = (".!?" if "end" in asp else "") + ("," if "comma" in asp else "")
    s = re.sub(r"([.!?])\1+", r"\1", s)  # "..." is one mark
    s = re.sub(r'[.,!?;:—–](?!\d)', lambda m: f" {m[0]} " if m[0] in keep else " ", s)
    s = s.replace('"', ' " ')
    if "case" not in asp:
        s = s.lower()
    words = s.split()
    small = {} if "numbers" in asp else DIGITS if cat == "number" else SMALL
    return [small.get(w.lower(), w) for w in words]


def end_mark(s):
    s = s.rstrip().rstrip('"')
    return s[-1] if s and s[-1] in ".?!" else ""


def right(text, e, strict=""):
    """[text] matches the expected text of testset line [e], or one of its "alt" texts."""
    got = norm(text, e["cat"], strict)
    return any(norm(x, e["cat"], strict) == got for x in [e["expected"]] + e.get("alt", []))


def score(work, name, alone=False):
    """With [alone], the model's own text is scored (no context-harness output needed)."""
    sys.stdout.reconfigure(encoding="utf-8")
    testset, results = os.path.join(work, f"testset-{name}.jsonl"), os.path.join(work, f"out-{name}.jsonl")
    need_file(testset, "testset")
    with open(testset, encoding="utf8") as f:
        exp = {d["id"]: d for d in map(json.loads, filter(str.strip, f))}
    if alone:
        outs = [{"id": i, "guarded": e["input"]} for i, e in exp.items()]
    else:
        need_file(results, "context-harness output")
        with open(results, encoding="utf8") as f:
            outs = [json.loads(l) for l in f if l.strip()]
    tot, raw_ok, sc_ok, helped, hurt, marked, mark_ok, mark_raw = (Counter() for _ in range(8))
    s_tot, s_raw, s_ok, s_helped, s_hurt, v_tot, v_raw, v_ok = (Counter() for _ in range(8))
    fails, seen = [], set()
    for d in outs:
        if d["id"] not in exp:
            sys.exit(f"{d['id']} is in {results} but not in {testset}")
        seen.add(d["id"])
        e = exp[d["id"]]
        c = e["cat"]
        tot[c] += 1
        r, g = right(e["input"], e), right(d["guarded"], e)
        raw_ok[c] += r
        sc_ok[c] += g
        helped[c] += g and not r
        hurt[c] += r and not g
        # end marks only where the expected text has one (commands, self-corrections, ... don't)
        if end_mark(e["expected"]):
            marked[c] += 1
            mark_raw[c] += end_mark(e["input"]) == end_mark(e["expected"])
            mark_ok[c] += end_mark(d["guarded"]) == end_mark(e["expected"])
        st = e.get("strict", "")
        rs, gs = r, g
        if st:
            rs, gs = right(e["input"], e, st), right(d["guarded"], e, st)
            s_tot[c] += 1
            s_raw[c] += rs
            s_ok[c] += gs
            s_helped[c] += gs and not rs
            s_hurt[c] += rs and not gs
        v = d["id"][-1]  # the voice letter
        v_tot[v] += 1
        v_raw[v] += rs
        v_ok[v] += gs
        if not g:
            fails.append((c, d["id"], e["say"], e["input"], e["segments"], d["guarded"], e["expected"]))
        elif not gs:
            fails.append((c, d["id"], e["say"], e["input"], e["segments"], d["guarded"], e["expected"], "strict: " + st))
    missing = len(exp) - len(seen)
    T = lambda k: sum(k.values())  # noqa: E731
    print(f"{'category':18s} {'n':>4s} {'model':>7s} {'+smart':>7s} {'helped':>7s} {'hurt':>5s}   end mark model / +smart / of"
          + ("   strict model / +smart / helped / hurt / of" if s_tot else ""))
    for c in sorted(tot):
        line = f"{c:18s} {tot[c]:4d} {raw_ok[c]:7d} {sc_ok[c]:7d} {helped[c]:7d} {hurt[c]:5d}   {mark_raw[c]:10d} / {mark_ok[c]:6d} / {marked[c]:<4d}"
        if s_tot[c]:
            line += f"   {s_raw[c]:12d} / {s_ok[c]:6d} / {s_helped[c]:6d} / {s_hurt[c]:4d} / {s_tot[c]}"
        print(line)
    line = f"{'TOTAL':18s} {T(tot):4d} {T(raw_ok):7d} {T(sc_ok):7d} {T(helped):7d} {T(hurt):5d}   {T(mark_raw):10d} / {T(mark_ok):6d} / {T(marked):<4d}"
    if s_tot:
        line += f"   {T(s_raw):12d} / {T(s_ok):6d} / {T(s_helped):6d} / {T(s_hurt):4d} / {T(s_tot)}"
    print(line)
    if s_tot:
        print("strict: a line counts only when its words and its strict parts are right (" +
              ", ".join(sorted({f"{e['cat']} {e['strict']}" for e in exp.values() if e.get("strict")})) + ")")
    print("by voice, model / +smart / of (strict where the line is):  " +
          "   ".join(f"{v}: {v_raw[v]} / {v_ok[v]} / {v_tot[v]}" for v in sorted(v_tot)))
    if alone:
        print("(--alone: the +smart columns are the model's own text too)")
    if missing:
        print(f"warning: {missing} lines of {testset} have no result in {results}")
    with open(os.path.join(work, f"fails-{name}{'-alone' if alone else ''}.json"), "w", encoding="utf8") as f:
        json.dump(fails, f, ensure_ascii=False, indent=1)


USAGE = __doc__.strip().split("\n", 2)[2]

if __name__ == "__main__":
    a = sys.argv[1:]
    threads = THREADS
    if "--threads" in a:
        k = a.index("--threads")
        threads = int(a[k + 1])
        del a[k:k + 2]
    if a[:1] == ["items"] and len(a) == 2:
        items(a[1])
    elif a[:1] == ["items2"] and len(a) == 2:
        items2(a[1])
    elif a[:1] in (["nemotron"], ["parakeet"]) and len(a) == 4:
        run_model(a[1], a[2], a[3], streaming=a[0] == "nemotron", name=a[0], threads=threads)
    elif a[:1] == ["score"] and len(a) in (3, 4) and a[3:] in ([], ["--alone"]):
        score(a[1], a[2], alone=a[3:] == ["--alone"])
    else:
        sys.exit("usage:\n" + USAGE)

"""Compare Kotlin harness output with a reference output, word by word.
usage: python compare.py INPUTS REF KOTLIN [--engine pcs|horizon]
For pcs: punctuation mark AND casing of every word must match exactly.
For horizon: punctuation must match exactly (the reference has no casing; the Kotlin casing is rule based).
Words that carry an abbreviation dot in the ASR input ("Dr.", "p.m.") are excluded from the mark comparison:
the engine keeps the ASR's dot there by design.
"""
import re, sys
from ref_common import read_inputs, strip_asr

args = [a for a in sys.argv[1:] if not a.startswith("--")]
engine = sys.argv[sys.argv.index("--engine") + 1] if "--engine" in sys.argv else "pcs"
if "--engine" in sys.argv:
    args.remove(engine)
inp, ref, kt = [read_inputs(p) for p in args[:3]]
assert len(inp) == len(ref) == len(kt), (len(inp), len(ref), len(kt))

ABBR = {"mr", "mrs", "ms", "dr", "prof", "jr", "sr", "vs", "etc", "inc", "ltd", "mt", "st", "approx", "dept", "est"}
def split(text):
    """-> list of (word_without_mark, mark)"""
    toks = text.split()
    out = []
    for t in toks:
        if t == "-":  # horizon reference: standalone dash after a word -> treated as ',' (engine mapping)
            if out: out[-1] = (out[-1][0], ",")
            continue
        m = re.match(r"^(.*?)([.,?!:;]*)$", t)
        w, mark = m.group(1), m.group(2)
        out.append((w, mark[-1:] if mark else ""))
    return out

unk_words = tot_words = punct_bad = case_bad = skipped = lines_equal = 0
for n, (i, r, k) in enumerate(zip(inp, ref, kt), 1):
    rw, kw = split(r), split(k)
    iw = [t for t in i.split()]
    if len(rw) != len(kw):
        print(f"[{n}] WORD COUNT DIFFERS ref={len(rw)} kotlin={len(kw)}\n  ref: {r}\n  kt:  {k}")
        punct_bad += 1
        continue
    # which words had abbreviation dots in the input
    stripped = strip_asr(i)
    abbr_idx = set()
    ti = i.split()
    j = 0
    for tok in ti:
        core = tok.strip(".,?!;:\u2026")
        if not core: continue
        if (tok.endswith(".") and (core.lower() in ABBR or re.fullmatch(r"[A-Za-z](\.[A-Za-z])+", core))):
            abbr_idx.add(j)
        j += 1
    diffs = []
    for idx, ((a, am), (b, bm)) in enumerate(zip(rw, kw)):
        tot_words += 1
        if idx in abbr_idx:
            skipped += 1
        elif am != bm and not (idx == len(rw) - 1 and am == "" and bm == "."):
            punct_bad += 1; diffs.append(f"punct@{idx} {a!r}: ref={am!r} kt={bm!r}")
        if "<unk>" in a.lower():
            # the Python package prints characters unknown to the tokenizer as "<unk>", but Kotlin keeps the original characters
            pat = re.escape(a.lower()).replace(re.escape("<unk>"), ".+")
            if re.fullmatch(pat, b.lower()):
                unk_words += 1
                continue
        if idx in abbr_idx:
            pass
        elif engine == "pcs" and a != b and a.lower() == b.lower():
            case_bad += 1; diffs.append(f"case@{idx}: ref={a!r} kt={b!r}")
        elif a.lower() != b.lower():
            diffs.append(f"WORD@{idx}: ref={a!r} kt={b!r}")
    if not diffs:
        lines_equal += 1
    else:
        print(f"[{n}] " + "; ".join(diffs) + f"\n  ref: {r}\n  kt:  {k}")
print(f"\nengine={engine} lines={len(inp)} identical_lines={lines_equal} words={tot_words} "
      f"punct_mismatches={punct_bad} case_mismatches={case_bad} abbreviation_words_skipped={skipped} unk_placeholder_words={unk_words}")

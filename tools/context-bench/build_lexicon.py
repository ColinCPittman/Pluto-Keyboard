"""Builds the sound-alike lexicon of the app's context stage (context model package).

The app does not ship a pronunciation dictionary and does not compute phoneme distances:
this script precomputes, for every word of CMUdict, exactly the candidate list that
rescore.py's Lexicon.candidates() returns (same vocabulary, same phoneme rule, same
filters, same ranking; equal scores keep rescore.py's scan order, which depends on Python's
set order, so run with PYTHONHASHSEED=0 like the reference runs), and
stores it in a compact binary file that the app memory-maps and binary-searches.

  python build_lexicon.py OUT.lex [--check N] [--v1]

Format version 2 (little-endian), see SoundAlikeLexicon.kt:
  magic "FCXLEX02", u32 nSections, nSections x (4-byte tag, u32 offset, u32 length),
  then the sections; each is one table:
    u32 nVocab, u32 nKeys, u32 nCands, u32 vocabBytes, u32 keyBytes
    u32 vocabOff[nVocab + 1], vocab UTF-8 blob          (candidate words / phrases)
    u32 keyOff[nKeys + 1],   key UTF-8 blob             (lookup keys, sorted by UTF-8 bytes)
    u32 candOff[nKeys + 1],  u16 cand[nCands]           (per key: vocab indices, best first)
  sections:
    SNDL  word -> sound-alike words (the version 1 table, unchanged)
    BNDR  word boundaries: "into" -> "in to", "in two"; "in to" -> "into"; "ice cream" -> "i scream"
          (a word whose CMUdict pronunciation is exactly that of two words, spelled as their
          concatenation or with a doubled letter at the junction dropped ("all ready" ->
          "already"); plus a few two-word <-> two-word pairs checked against CMUdict)
    CAPS  lowercase word -> its usual capitalized form ("jordan" -> "Jordan"; only words that
          are clearly more common capitalized in the AOSP word list)
    LOWR  set (no candidates): words that are clearly more common lowercase ("toaster")
  --v1 writes the old single-table format ("FCXLEX01" + the SNDL table), the only one that
  apps before the word-boundary/capitalization options can read.

Data: CMUdict (BSD-style, Carnegie Mellon University) through the `pronouncing` package;
word frequencies from `wordfreq` (data CC-BY-SA 4.0); case and word-list data from the AOSP
English word list dictionaries/en_US_wordlist.combined.gz (Apache-2.0). Needs the
context-bench venv.
"""

from __future__ import annotations

import argparse
import gzip
import random
import re
import struct
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from guard import FUNCTION_LOCK, _is_inflection, sounds_alike  # noqa: E402
from rescore import Lexicon  # noqa: E402

K = 6


def build(lex: Lexicon) -> dict[str, dict[str, float]]:
    """word -> {candidate: score} (all candidates, not yet cut to K)"""
    # index vocabulary pronunciations by (length, position, pronunciation with that phoneme masked)
    # (entries keep their position in lex.by_len, because Lexicon.candidates() scans that list and
    # the scan order decides the order of equal scores)
    masked: dict[tuple, list[tuple[str, tuple, int]]] = {}
    for n, entries in lex.by_len.items():
        for pos, (c, cp) in enumerate(entries):
            for i in range(n):
                masked.setdefault((n, i, cp[:i] + cp[i + 1:]), []).append((c, cp, pos))
    out: dict[str, dict[str, float]] = {}
    for w in sorted(lex.pron):
        if w in FUNCTION_LOCK or len(w) < 2 or any(ch.isdigit() for ch in w):
            continue
        scores: dict[str, float] = {}
        first: dict[str, tuple[int, int]] = {}
        for pi, p in enumerate(lex.pron[w]):
            n = len(p)
            seen = set()
            for i in range(n):
                for c, cp, pos in masked.get((n, i, p[:i] + p[i + 1:]), ()):
                    if pos in seen:
                        continue
                    seen.add(pos)
                    if c == w or c in FUNCTION_LOCK:
                        continue
                    diff = sum(1 for a, b in zip(p, cp) if a != b)
                    if diff > 1:
                        continue
                    if _is_inflection(w, c) or _is_inflection(c, w):
                        continue
                    if not sounds_alike(w, c)[0]:
                        continue
                    scores[c] = max(scores.get(c, -1), lex.zipf(c, "en") + (1.0 if diff == 0 else 0.0))
                    first[c] = min(first.get(c, (pi, pos)), (pi, pos))
        if scores:
            out[w] = {c: (scores[c], first[c]) for c in scores}
    return out


def ranked(scores: dict[str, tuple]) -> list[str]:
    # same as Lexicon.candidates(), which does sorted(out, key=-score) over insertion (= scan) order
    return sorted(scores, key=lambda c: (-scores[c][0], scores[c][1]))[:K]


def table_bytes(table: dict[str, list[str]]) -> bytes:
    """One table (without magic): counts, vocab, keys, candidate lists."""
    vocab = sorted({c for cs in table.values() for c in cs})
    if len(vocab) >= 65536:
        raise SystemExit(f"vocabulary too large for u16 indices: {len(vocab)}")
    vidx = {w: i for i, w in enumerate(vocab)}
    keys = sorted(table, key=lambda w: w.encode("utf-8"))

    def blob(words):
        offs, data = [0], bytearray()
        for w in words:
            data += w.encode("utf-8")
            offs.append(len(data))
        return offs, bytes(data)

    voff, vblob = blob(vocab)
    koff, kblob = blob(keys)
    coff, cands = [0], []
    for k in keys:
        cands += [vidx[c] for c in table[k]]
        coff.append(len(cands))
    out = bytearray()
    out += struct.pack("<5I", len(vocab), len(keys), len(cands), len(vblob), len(kblob))
    out += struct.pack(f"<{len(voff)}I", *voff) + vblob
    out += struct.pack(f"<{len(koff)}I", *koff) + kblob
    out += struct.pack(f"<{len(coff)}I", *coff)
    out += struct.pack(f"<{len(cands)}H", *cands)
    return bytes(out)


def write(sections: dict[str, dict[str, list[str]]], path: Path, v1: bool = False):
    if v1:
        data = b"FCXLEX01" + table_bytes(sections["SNDL"])
    else:
        bodies = [(tag.encode("ascii"), table_bytes(t)) for tag, t in sections.items()]
        off = 8 + 4 + 12 * len(bodies)
        data = bytearray(b"FCXLEX02" + struct.pack("<I", len(bodies)))
        for tag, body in bodies:
            assert len(tag) == 4
            data += tag + struct.pack("<2I", off, len(body))
            off += len(body)
        for _, body in bodies:
            data += body
        data = bytes(data)
    path.write_bytes(data)
    desc = ", ".join(f"{tag} {len(t)} keys / {sum(len(c) for c in t.values())} candidates"
                     for tag, t in sections.items() if not v1 or tag == "SNDL")
    print(f"{path}: {'v1' if v1 else 'v2'}, {desc}, {len(data) / 1e6:.2f} MB")


# ----------------------------------------------------------------------------
# Word boundaries and case (format version 2)

AOSP_WORDLIST = HERE.parent.parent / "dictionaries" / "en_US_wordlist.combined.gz"
BOUNDARY_K = 4
# a boundary part / word must be at least this common in the AOSP word list (f is 0..255, log scale)
BOUNDARY_MIN_F = 80
# ... and the one word at least this common ("awhile" is 73)
BOUNDARY_MIN_F_WORD = 60
# never parts of a split (suffix-like or filler "words")
BOUNDARY_BAD_PARTS = {"er", "s", "ed", "ing", "ly", "es", "y", "um", "uh", "eh", "ah", "oh", "ha", "hm", "mm",
                      "teen", "ty"}
BOUNDARY_BAD_WORDS = {"cannot"}
NUMBER_WORDS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
                "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
                "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred",
                "thousand", "million", "billion"}
# two-word <-> two-word pairs that sound the same (the boundary moves), and each is checked against CMUdict
BOUNDARY_SEED_2TO2 = [("ice cream", "i scream"), ("a nice", "an ice"), ("a name", "an aim"),
                      ("a notion", "an ocean")]
# case. A form must be at least this common, and this much more common than the other case
CASE_MIN_F = 40
CASE_MARGIN = 25
LOWER_MIN_F = 50
# lowercase-dominant in the word list but often names, months, ..., so never lowercased
LOWER_EXCEPTIONS = {"may", "will", "bill", "mark", "rose", "frank", "grace", "hope", "joy", "faith", "pat",
                    "sue", "jack", "art", "bob", "ray", "dawn", "summer", "autumn", "june", "april", "august",
                    "mike", "jay", "jean", "max", "drew", "chase", "rich", "victor", "earl", "lily", "ivy",
                    "god", "lord", "mom", "dad", "mum", "grandma", "grandpa"}


def load_aosp(path: Path = AOSP_WORDLIST) -> dict[str, dict[str, int]]:
    """lowercase word -> {form: frequency} (frequency = max(f, originalFreq))."""
    forms: dict[str, dict[str, int]] = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            m = re.match(r"\s*word=([^,]+),f=(\d+)(?:.*originalFreq=(\d+))?", line)
            if not m:
                continue
            w = m.group(1)
            fr = max(int(m.group(2)), int(m.group(3) or 0))
            d = forms.setdefault(w.lower(), {})
            d[w] = max(d.get(w, 0), fr)
    return forms


def lower_freq(aosp, w: str) -> int:
    return aosp.get(w, {}).get(w, -1)


def build_boundary(lex: Lexicon, aosp) -> dict[str, list[str]]:
    prons: dict[str, set] = {}
    for entries in lex.by_len.values():
        for w, p in entries:
            prons.setdefault(w, set()).add(p)
    by_pron: dict[tuple, set] = {}
    for w, ps in prons.items():
        for p in ps:
            by_pron.setdefault(p, set()).add(w)

    def part_ok(w):
        if w in ("a", "i"):
            return True
        return len(w) >= 2 and "'" not in w and w not in BOUNDARY_BAD_PARTS and lower_freq(aosp, w) >= BOUNDARY_MIN_F

    pairs: dict[str, dict[str, int]] = {}

    def add(a, b, score):
        d = pairs.setdefault(a, {})
        d[b] = max(d.get(b, -1), score)

    def spelled_splits(W):
        """(s1, s2) with s1 + s2 == W, or W with a doubled letter at the junction dropped
        ("all" + "ready" -> "already")."""
        out = set()
        for i in range(1, len(W)):
            out.add((W[:i], W[i:]))
            # doubled letter, where the char at the junction is written twice in the two words
            out.add((W[:i] + W[i - 1], W[i:]))
            out.add((W[:i], W[i - 1:]))
        return out

    for W, ps in prons.items():
        if "'" in W or W in BOUNDARY_BAD_WORDS or W in NUMBER_WORDS or W in FUNCTION_LOCK:
            continue
        if lower_freq(aosp, W) < BOUNDARY_MIN_F_WORD:
            continue
        for s1, s2 in spelled_splits(W):
            if not (part_ok(s1) and part_ok(s2)):
                continue
            for p1 in prons.get(s1, ()):
                for p2 in prons.get(s2, ()):
                    if p1 + p2 not in ps:
                        continue
                    # the spelled parts and their homophones ("in to" / "in two" -> "into")
                    for w1 in by_pron.get(p1, ()):
                        if not part_ok(w1) or (w1 != s1 and len(w1) == 1):
                            continue
                        for w2 in by_pron.get(p2, ()):
                            if not part_ok(w2) or (w2 != s2 and len(w2) == 1):
                                continue
                            phrase = w1 + " " + w2
                            score = min(255 if x in ("a", "i") else lower_freq(aosp, x) for x in (w1, w2))
                            # the spelled split first, then its homophones
                            if (w1, w2) == (s1, s2):
                                score += 1000
                            add(W, phrase, score)
                            add(phrase, W, lower_freq(aosp, W))
    for a, b in BOUNDARY_SEED_2TO2:
        def joined(phrase):
            w1, w2 = phrase.split()
            return {q1 + q2 for q1 in prons.get(w1, ()) for q2 in prons.get(w2, ())}
        if not (joined(a) & joined(b)):
            print(f"seed pair {a!r} / {b!r}: pronunciations differ (or a word is missing), skipped")
            continue
        add(a, b, 200)
        add(b, a, 200)
    return {k: [c for c, _ in sorted(v.items(), key=lambda kv: (-kv[1], kv[0]))][:BOUNDARY_K]
            for k, v in pairs.items()}


def build_case(aosp) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    caps: dict[str, list[str]] = {}
    lowr: dict[str, list[str]] = {}
    for lw, fs in aosp.items():
        if not lw[:1].isalpha() or not all(ch.isalpha() or ch == "'" for ch in lw):
            continue
        fl = fs.get(lw, -1)
        cased = [(f, w) for w, f in fs.items() if w != lw]
        fc, wc = max(cased) if cased else (-1, None)
        # title case ("Jordan") or a name with inner capitals ("McDonald"), never all caps ("TV")
        if (wc and wc[0].isupper() and not (len(wc) > 1 and wc.isupper())
                and fc >= CASE_MIN_F and fc >= fl + CASE_MARGIN):
            caps[lw] = [wc]
        if fl >= LOWER_MIN_F and fc < 60 and fl >= fc + CASE_MARGIN and lw not in LOWER_EXCEPTIONS and lw != "i":
            lowr[lw] = []
    return caps, lowr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--check", type=int, default=300, help="compare N random words with Lexicon.candidates()")
    ap.add_argument("--dump", default="", help="also write the table as TSV (word<TAB>cand1 cand2 ...)")
    ap.add_argument("--v1", action="store_true", help="write the old single-table format (sound-alike words only)")
    args = ap.parse_args()
    t0 = time.time()
    lex = Lexicon()
    table = build(lex)
    print(f"built in {time.time() - t0:.0f} s")
    # rescore.py's own (slow) implementation must find the same candidates with the same
    # scores, but only the order of equal scores may differ (Python's set order is random)
    rnd = random.Random(1)
    words = rnd.sample(sorted(lex.pron), args.check) + ["bulls", "meat", "flower", "whether", "our", "jim"]
    bad = ties = 0
    for w in words:
        mine = table.get(w, {})
        ref_all = Lexicon.candidates.__wrapped__(lex, w, 100000)
        ref = lex.candidates(w)
        if set(ref_all) != set(mine):
            bad += 1
            print("MISMATCH", w, sorted(ref_all), sorted(mine))
        elif [mine[c][0] for c in ref] != [mine[c][0] for c in ranked(mine)]:
            bad += 1
            print("ORDER MISMATCH", w, ref, ranked(mine))
        elif list(ref) != ranked(mine):
            ties += 1
    print(f"checked {len(words)} words: {bad} mismatches, {ties} differ only in the order of equal scores")
    table = {w: ranked(sc) for w, sc in table.items()}
    sections = {"SNDL": table}
    if not args.v1:
        aosp = load_aosp()
        sections["BNDR"] = build_boundary(lex, aosp)
        caps, lowr = build_case(aosp)
        sections["CAPS"] = caps
        sections["LOWR"] = lowr
        for k in ("into", "in to", "everyday", "every day", "anymore", "any more", "maybe", "may be",
                  "already", "all ready", "altogether", "apart", "a part", "awhile", "a while", "ice cream"):
            print(f"  BNDR {k!r} -> {sections['BNDR'].get(k)}")
    write(sections, Path(args.out), v1=args.v1)
    if args.dump:
        with open(args.dump, "w", encoding="utf-8") as f:
            for tag, t in sections.items():
                for w in sorted(t):
                    prefix = "" if tag == "SNDL" else tag + "\t"
                    f.write(prefix + w + "\t" + (" ".join(t[w]) if tag == "SNDL" else " | ".join(t[w])) + "\n")


if __name__ == "__main__":
    main()

"""Builds the typing confusion list for Smart corrections for typing: pairs of common real
words one key off from each other ("an" / "and", "or" / "of", "form" / "from"), which the
keyboard can't fix while typing because both are words.

  python build_typing_confusions.py [--min-typed F] [--min-intended F]
    -> voiceinput-shared/src/main/resources/org/futo/voiceinput/shared/context/typing-confusions-en.txt

One line per typed word: "typed intended1 intended2 ...". A typed word becomes an intended one
by one slip: a neighbouring key instead of the right one, a missed key, an extra key next to a
letter (or a doubled one), or two letters swapped. Frequencies come from the AOSP word list
(dictionaries/en_US_wordlist.combined.gz, 0..255); both words must be common lowercase words
without flags (no offensive, abbreviation, ...).
"""

from __future__ import annotations

import gzip
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORDLIST = ROOT / "dictionaries/en_US_wordlist.combined.gz"
OUT = ROOT / "voiceinput-shared/src/main/resources/org/futo/voiceinput/shared/context/typing-confusions-en.txt"

ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
# row offsets in key widths (staggered QWERTY)
OFFSET = [0.0, 0.25, 0.75]

# both are real words, but one is far more common as a typo than as the meant word, or the pair
# is a style choice rather than a mistake
EXCLUDE: set[tuple[str, str]] = set()
# common slips that aren't one key off on QWERTY (typed, intended)
EXTRA = [("then", "than"), ("than", "then"), ("it", "is"), ("is", "it")]


def key_neighbours() -> dict[str, set[str]]:
    pos = {}
    for r, row in enumerate(ROWS):
        for c, ch in enumerate(row):
            pos[ch] = (c + OFFSET[r], r)
    out: dict[str, set[str]] = {ch: set() for ch in pos}
    for a, (xa, ya) in pos.items():
        for b, (xb, yb) in pos.items():
            if a != b and abs(ya - yb) <= 1 and abs(xa - xb) <= 1.0:
                out[a].add(b)
    return out


def load(min_f: int) -> dict[str, int]:
    words: dict[str, int] = {}
    with gzip.open(WORDLIST, "rt", encoding="utf-8") as f:
        for line in f:
            m = re.match(r"\s*word=([^,]+),f=(\d+),flags=([^,]*)", line)
            if not m or m.group(3):
                continue
            w, fr = m.group(1), int(m.group(2))
            if fr >= min_f and re.fullmatch(r"[a-z]+", w):
                words[w] = max(words.get(w, 0), fr)
    return words


def slips(w: str, nb: dict[str, set[str]]):
    """Words the user may have meant when typing [w] (unfiltered)."""
    letters = "abcdefghijklmnopqrstuvwxyz"
    for i, ch in enumerate(w):
        for o in nb.get(ch, ()):  # neighbouring key
            yield w[:i] + o + w[i + 1:]
    for i in range(len(w) + 1):  # missed key
        for o in letters:
            yield w[:i] + o + w[i:]
    for i in range(len(w)):  # extra key, next to (or the same as) a letter beside it
        side = {w[i - 1]} if i > 0 else set()
        if i + 1 < len(w):
            side.add(w[i + 1])
        if any(w[i] == s or w[i] in nb.get(s, ()) for s in side):
            yield w[:i] + w[i + 1:]
    for i in range(len(w) - 1):  # swapped letters
        yield w[:i] + w[i + 1] + w[i] + w[i + 2:]


CONTRACTIONS = """it's that's there's here's what's who's he's she's let's we're you're they're i'm i'll you'll we'll
they'll he'll she'll it'll i've we've you've they've i'd you'd he'd she'd we'd they'd can't won't don't doesn't didn't
isn't aren't wasn't weren't couldn't wouldn't shouldn't hasn't haven't hadn't""".split()


def contraction_slips(c: str, nb: dict[str, set[str]]):
    """Typed forms of the contraction [c] with one slip (the apostrophe is always kept)."""
    letters = "abcdefghijklmnopqrstuvwxyz"
    for i, ch in enumerate(c):
        if ch == "'":
            continue
        for o in nb[ch]:  # neighbouring key instead
            yield c[:i] + o + c[i + 1:]
        yield c[:i] + c[i + 1:]  # missed key
    for i in range(len(c) + 1):  # extra key, the same as or next to a letter beside it
        side = [x for x in (c[i - 1] if i > 0 else "", c[i] if i < len(c) else "") if x.isalpha()]
        for o in letters:
            if any(o == x or o in nb[x] for x in side):
                yield c[:i] + o + c[i:]
    for i in range(len(c) - 1):  # swapped letters
        if c[i] != "'" and c[i + 1] != "'":
            yield c[:i] + c[i + 1] + c[i] + c[i + 2:]


def main():
    args = sys.argv[1:]
    min_typed = int(args[args.index("--min-typed") + 1]) if "--min-typed" in args else 80
    min_intended = int(args[args.index("--min-intended") + 1]) if "--min-intended" in args else 120
    nb = key_neighbours()
    typed_words = load(min_typed)
    intended_words = load(min_intended)
    lines = []
    pairs = 0
    table: dict[str, dict[str, int]] = {}
    for w in typed_words:
        if len(w) < 2:
            continue
        for c in slips(w, nb):
            if c != w and c in intended_words and len(c) >= 2 and (w, c) not in EXCLUDE:
                table.setdefault(w, {})[c] = intended_words[c]
    # contractions, where the typed form has the apostrophe (so it is never a word), as in "kit's" -> "it's"
    for c in CONTRACTIONS:
        for t in set(contraction_slips(c, nb)):
            if t != c and t not in CONTRACTIONS and re.fullmatch(r"[a-z]+'[a-z]+", t) and len(t) >= 4:
                table.setdefault(t, {})[c] = 255
    # a slip works both ways, so if "trick" may be a slip of "truck", "truck" may be one of "trick",
    # when both are common enough to be typed
    for w, cs in list(table.items()):
        for c in cs:
            if c in typed_words and (c, w) not in EXCLUDE and "'" not in w:
                table.setdefault(c, {}).setdefault(w, typed_words[w])
    for w in sorted(list(typed_words) + sorted(t for t in table if "'" in t), key=lambda x: -typed_words.get(x, 0)):
        cands = sorted(table.get(w, {}), key=lambda x: -table[w][x])
        cands += [c for t, c in EXTRA if t == w and c not in cands]
        if cands:
            lines.append(w + " " + " ".join(cands))
            pairs += len(cands)
    header = "# typed word, then the words it may be a one-key slip of (tools/context-bench/build_typing_confusions.py)"
    OUT.write_text(header + "\n" + "\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(lines)} typed words, {pairs} pairs -> {OUT}")


if __name__ == "__main__":
    main()

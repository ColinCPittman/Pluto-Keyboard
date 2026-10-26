"""Builds the sound-alike data the context stage uses to tell exact homophones ("meat" /
"meet") from near sound-alikes a voice model can confuse ("bulls" / "bowls": one similar
sound different), which need much more evidence to be swapped, and from those it can't
("fan" / "phone": a clearly different vowel), which are never swapped.

  1. context-harness lexdump <soundalike-en.lex> > lexdump.tsv
  2. the words of lexdump.tsv, one per line -> words.txt
     powershell -File sapi_phonemes.ps1 words.txt phonemes.tsv   (Windows text-to-speech phonemes)
  3. python build_homophones.py lexdump.tsv phonemes.tsv [more phonemes.tsv ...]
       -> voiceinput-shared/src/main/resources/org/futo/voiceinput/shared/context/homophones-en.txt

Only lexicon pairs (word -> sound-alike candidate) count. Homophones: pronounced exactly the
same by the speech engine, plus a few pairs it pronounces in their weak forms ("to" / "too");
one group per line. Confusable: the same sounds but one, and that one is a similar sound
(CONFUSABLE: lax/tense vowels, voicing, ...); one pair per line, starting with "~".
"""

from __future__ import annotations

import sys
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / "voiceinput-shared/src/main/resources/org/futo/voiceinput/shared/context/homophones-en.txt"

# pronounced alike in normal speech, but not by the engine (weak forms, regional vowels)
EXTRA = [
    "to too two", "our hour", "red read", "there their they're", "your you're", "its it's", "lead led",
    "for four fore", "than then", "are r", "of have",
]


# Windows SAPI phone ids (1-based) -> ARPAbet-like symbols
SAPI = "- ! & , . ? _ 1 2 aa ae ah ao aw ax ay b ch d dh eh er ey f g h ih iy jh k l m n ng ow oy p r s sh t th uh uw v w y z zh".split()
SKIP = {"-", "!", "&", ",", ".", "?", "_", "1", "2"}

# sounds a voice model can mix up (similar vowels, voicing, place)
CONFUSABLE = {
    frozenset(p) for p in [
        ("iy", "ih"), ("ih", "eh"), ("eh", "ae"), ("eh", "ey"), ("aa", "ao"), ("ah", "aa"), ("ah", "ax"), ("ax", "ih"),
        ("uw", "uh"), ("uh", "ow"), ("ow", "uw"), ("ao", "ow"), ("er", "ah"), ("ax", "er"),
        ("p", "b"), ("t", "d"), ("k", "g"), ("f", "v"), ("s", "z"), ("th", "dh"), ("ch", "jh"), ("sh", "zh"),
        ("m", "n"), ("n", "ng"), ("th", "f"), ("ch", "sh"), ("jh", "zh"),
    ]
}


def main():
    lexdump, *phfiles = sys.argv[1:]
    ph = {}
    for f in phfiles:
        for line in open(f, encoding="utf-8-sig"):
            p = line.rstrip("\n").split("\t")
            if len(p) == 2 and p[1].strip():
                ph[p[0]] = tuple(q for q in (SAPI[int(x) - 1] for x in p[1].split() if 0 < int(x) <= len(SAPI)) if q not in SKIP)
    parent = {}

    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        parent[find(a)] = find(b)

    near = set()
    for line in open(lexdump, encoding="utf-8"):
        k, _, c = line.rstrip("\n").partition("\t")
        for w in c.split():
            if k not in ph or w not in ph or not ph[k]:
                continue
            if ph[k] == ph[w]:
                union(k, w)
            elif len(ph[k]) == len(ph[w]):
                diff = [frozenset((a, b)) for a, b in zip(ph[k], ph[w]) if a != b]
                if len(diff) == 1 and diff[0] in CONFUSABLE:
                    near.add(tuple(sorted((k, w))))
    for g in EXTRA:
        ws = g.split()
        for w in ws[1:]:
            union(ws[0], w)
    groups = {}
    for w in parent:
        groups.setdefault(find(w), []).append(w)
    lines = sorted(" ".join(sorted(g)) for g in groups.values() if len(g) > 1)
    pairs = sorted("~ " + " ".join(p) for p in near if find(p[0]) != find(p[1]))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        "# Homophone groups, then \"~\" pairs of near sound-alikes a voice model can confuse"
        " (see tools/context-bench/build_homophones.py)\n" + "\n".join(lines + pairs) + "\n", encoding="utf-8")
    print(f"{OUT}: {len(lines)} groups, {len(pairs)} confusable pairs")


if __name__ == "__main__":
    main()

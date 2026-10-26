"""Deterministic guard for the voice-input context model.

Diffs the LLM output against the input at word level and only lets through:
  * punctuation changes between words (from a small allowed set),
  * first-letter capitalization changes,
  * word substitutions where the two words sound alike (Double Metaphone),
  * merges/splits of up to 2+2 words whose joined spelling sounds the same
    ("every day" <-> "everyday"), as long as no apostrophe is added/removed.
Everything else (insertions, deletions, rewording) is rejected and the
original words are kept.

Written to port 1:1 to Kotlin: plain loops, no library diffing.  The only
dependency is Double Metaphone (Python: `metaphone` package; Kotlin/Java:
org.apache.commons.codec.language.DoubleMetaphone, or copy that one class).
"""

from __future__ import annotations

from dataclasses import dataclass, field

from metaphone import doublemetaphone

ALLOWED_PUNCT = set(".,?!;:")
SENTENCE_END = set(".?!")


# ----------------------------------------------------------------------------
# Tokenizing, so that text = gap[0] word[0] gap[1] word[1] ... word[n-1] gap[n]

def _is_word_char(c: str) -> bool:
    return c.isalnum()


def tokenize(text: str) -> tuple[list[str], list[str]]:
    """Returns (words, gaps) with len(gaps) == len(words) + 1.

    A word is a run of letters/digits, optionally joined by ' ’ or - between
    two word chars (so "don't", "I-95", "6:45" -> "6", ":", "45" is fine).
    """
    words: list[str] = []
    gaps: list[str] = []
    i, n = 0, len(text)
    gap_start = 0
    while i < n:
        if _is_word_char(text[i]):
            gaps.append(text[gap_start:i])
            j = i
            while j < n:
                if _is_word_char(text[j]):
                    j += 1
                elif text[j] in "'’-" and j + 1 < n and _is_word_char(text[j + 1]) and j > i:
                    j += 1
                else:
                    break
            words.append(text[i:j])
            i = j
            gap_start = j
        else:
            i += 1
    gaps.append(text[gap_start:])
    return words, gaps


def norm_word(w: str) -> str:
    return w.lower().replace("’", "'")


# ----------------------------------------------------------------------------
# Alignment is an LCS over normalized words -> list of blocks

@dataclass
class Block:
    op: str  # "equal" | "replace" | "insert" | "delete"
    i1: int
    i2: int
    j1: int
    j2: int


def align(a: list[str], b: list[str]) -> list[Block]:
    n, m = len(a), len(b)
    # dp[i][j] = LCS length of a[i:], b[j:]
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n - 1, -1, -1):
        for j in range(m - 1, -1, -1):
            if a[i] == b[j]:
                dp[i][j] = dp[i + 1][j + 1] + 1
            else:
                dp[i][j] = max(dp[i + 1][j], dp[i][j + 1])
    blocks: list[Block] = []
    i = j = 0
    pi = pj = 0  # start of the current non-equal run

    def flush(i_end: int, j_end: int):
        if pi == i_end and pj == j_end:
            return
        if pi == i_end:
            op = "insert"
        elif pj == j_end:
            op = "delete"
        else:
            op = "replace"
        blocks.append(Block(op, pi, i_end, pj, j_end))

    while i < n and j < m:
        if a[i] == b[j]:
            flush(i, j)
            blocks.append(Block("equal", i, i + 1, j, j + 1))
            i += 1
            j += 1
            pi, pj = i, j
        elif dp[i + 1][j] >= dp[i][j + 1]:
            i += 1
        else:
            j += 1
    flush(n, m)
    return blocks


# ----------------------------------------------------------------------------
# Sound-alike test

NEGATIONS = {"not", "no", "never", "cannot", "nor"}


def _is_negation(w: str) -> bool:
    return w.endswith("n't") or w in NEGATIONS


# Swapping two of these changes tense/person/modality, never a mishearing worth fixing
# ("is" -> "was", "can" -> "could", "he" -> "she"), but "are" -> "our" etc. still allowed.
FUNCTION_LOCK = {
    "is", "was", "are", "were", "am", "be", "been", "being", "has", "have", "had", "do", "does",
    "did", "will", "would", "can", "could", "shall", "should", "may", "might", "must",
    "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
    "a", "an", "the", "this", "that", "these", "those", "as", "at", "in", "on", "of",
}
SUFFIXES = ("s", "es", "'s", "s'", "ed", "d", "ing", "ly", "er", "est")


def _is_inflection(a: str, b: str) -> bool:
    """b is a plus an inflectional suffix ("bagel" -> "bagels", "Kowalski" -> "Kowalski's")."""
    return any(b == a + suf for suf in SUFFIXES) or (b.endswith("ies") and a.endswith("y") and b[:-3] == a[:-1])


def _codes(w: str) -> list[str]:
    p, s = doublemetaphone(w.replace("'", ""))
    # treat a leading aspirate like a leading vowel ("hour" HR ~ "our" AR)
    return [("A" + c[1:]) if c.startswith("H") else c for c in (p, s) if c]


def _lev(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev[len(b)]


def sounds_alike(a: str, b: str) -> tuple[bool, str]:
    a, b = norm_word(a), norm_word(b)
    if a == b:
        return True, "same"
    if any(c.isdigit() for c in a + b):
        return False, "digits"
    if _is_negation(a) != _is_negation(b):
        return False, "negation"
    if a in FUNCTION_LOCK and b in FUNCTION_LOCK:
        return False, "function-word swap"
    if _is_inflection(a, b) or _is_inflection(b, a):
        return False, "inflection"
    ca, cb = _codes(a), _codes(b)
    if not ca or not cb:
        return False, "no-code"
    for x in ca:
        for y in cb:
            if x == y:
                # one-letter codes are too weak when one side is a 1-letter word ("a" vs "I")
                if len(x) == 1 and min(len(a), len(b)) <= 1:
                    return False, "too-short"
                return True, f"metaphone {x}"
    # (A looser "codes within edit distance 1" rule was tried and dropped, because it lets
    # break -> brain and whether -> leather through.)
    return False, f"codes {'/'.join(ca)} vs {'/'.join(cb)}"


def joined_sounds_alike(a_words: list[str], b_words: list[str]) -> tuple[bool, str]:
    a = "".join(norm_word(w) for w in a_words)
    b = "".join(norm_word(w) for w in b_words)
    if a.count("'") != b.count("'"):
        return False, "contraction change"
    if a.replace("'", "") == b.replace("'", ""):
        return True, "respaced"
    ok, why = sounds_alike(a, b)
    if ok and why.startswith("metaphone"):  # exact code match only for merges/splits
        return True, "joined " + why
    return False, "joined " + why


# ----------------------------------------------------------------------------
# Gaps (punctuation between words)

def _punct(g: str) -> str:
    return "".join(c for c in g if not c.isspace())


def choose_gap(in_gap: str, out_gap: str, is_last: bool) -> tuple[str, bool]:
    """Returns (gap, changed)."""
    pin, pout = _punct(in_gap), _punct(out_gap)
    if "?" in pout or "!" in pout:  # "right?." / "right,?" -> "right?"
        pout = "".join(c for c in pout if c not in ".,;:")
    if pin == pout:
        return in_gap, False
    if not all(c in ALLOWED_PUNCT for c in pin) or not all(c in ALLOWED_PUNCT for c in pout):
        return in_gap, False
    if len(pout) > 2:
        return in_gap, False
    # Only change the TYPE of an existing mark (", and" -> "? And", "here." -> "here?").
    # Adding a mark between words or deleting one is the punctuation model's job.
    # One exception is that the end of the text may gain a final mark.
    if not pout or (not pin and not is_last):
        return in_gap, False
    # never drop a question/exclamation mark the ASR produced
    for c in "?!":
        if c in pin and c not in pout:
            return in_gap, False
    ws = "" if is_last else (" " if any(c.isspace() for c in in_gap) or not in_gap else "")
    return pout + ws, True


def _case_ok(in_w: str, out_w: str) -> bool:
    """Only allow changing the case of the first letter."""
    return in_w[1:] == out_w[1:]


# ----------------------------------------------------------------------------

@dataclass
class Decision:
    op: str
    src: str
    dst: str
    accepted: bool
    reason: str


@dataclass
class GuardResult:
    text: str
    decisions: list[Decision] = field(default_factory=list)

    @property
    def accepted(self):
        return [d for d in self.decisions if d.accepted]

    @property
    def rejected(self):
        return [d for d in self.decisions if not d.accepted]


def guard(inp: str, out: str) -> GuardResult:
    out = out.strip()
    iw, ig = tokenize(inp)
    ow, og = tokenize(out)
    res = GuardResult(text=inp)
    if not ow:
        res.decisions.append(Decision("empty", inp, out, False, "empty output"))
        return res
    blocks = align([norm_word(w) for w in iw], [norm_word(w) for w in ow])

    # One entry per result word, as [input-side form, output-side form, gap before it in the
    # input, gap before it in the output, whether the output's casing may be used]
    words: list[list] = []
    gaps_after: list[str] = []
    n_in = len(iw)

    def take_gap(i_end: int, j_end: int):
        # gap after input word i_end-1 / output word j_end-1
        is_last = i_end == n_in
        o = og[j_end] if j_end < len(og) else ""
        g, changed = choose_gap(ig[i_end], o, is_last)
        if changed:
            res.decisions.append(Decision("punct", _punct(ig[i_end]) or "_", _punct(g) or "_", True, "punct"))
        elif _punct(ig[i_end]) != _punct(o):
            res.decisions.append(Decision("punct", _punct(ig[i_end]) or "_", _punct(o) or "_", False,
                                          "punct not allowed"))
        return g

    def keep_input(i1: int, i2: int):
        for i in range(i1, i2):
            words.append([iw[i], iw[i], ig[i], ig[i], False])
            gaps_after.append(ig[i + 1])

    for b in blocks:
        if b.op == "equal":
            words.append([iw[b.i1], ow[b.j1], ig[b.i1], og[b.j1], True])
            gaps_after.append(take_gap(b.i2, b.j2))
        elif b.op == "replace":
            src, dst = iw[b.i1:b.i2], ow[b.j1:b.j2]
            if len(src) == 1 and len(dst) == 1:
                ok, why = sounds_alike(src[0], dst[0])
            elif len(src) <= 2 and len(dst) <= 2:
                ok, why = joined_sounds_alike(src, dst)
            elif len(src) == len(dst):
                # several adjacent substitutions are accepted only if every pair sounds alike
                pairs = [sounds_alike(x, y) for x, y in zip(src, dst)]
                ok = all(p[0] for p in pairs)
                why = "; ".join(p[1] for p in pairs)
            else:
                ok, why = False, "rewording"
            res.decisions.append(Decision("subst", " ".join(src), " ".join(dst), ok, why))
            if ok:
                for k, w in enumerate(dst):
                    words.append([w, w, ig[b.i1] if k == 0 else " ", og[b.j1 + k], True])
                    if k < len(dst) - 1:
                        gaps_after.append(" ")
                gaps_after.append(take_gap(b.i2, b.j2))
            else:
                keep_input(b.i1, b.i2)
        elif b.op == "delete":
            res.decisions.append(Decision("delete", " ".join(iw[b.i1:b.i2]), "", False, "deletion"))
            keep_input(b.i1, b.i2)
        elif b.op == "insert":
            res.decisions.append(Decision("insert", "", " ".join(ow[b.j1:b.j2]), False, "insertion"))
            # the gap after the previous word was taken from the output, which preceded
            # the inserted words, so re-take it against the gap after the insert
            if words:
                gaps_after[-1] = choose_gap(ig[b.i1], og[b.j2] if b.j2 < len(og) else "", b.i1 == n_in)[0]

    def sentence_end(g: str) -> bool:
        return any(c in SENTENCE_END for c in _punct(g))

    def plain(w: str) -> bool:  # only the first letter may carry case
        return w[1:] == w[1:].lower()

    # Casing pass. The output's first-letter case is used only where the output's
    # sentence structure around the word matches the final one (so a rejected "?"
    # cannot leave a stray capital behind), and words that now start a sentence get
    # capitalized.
    text = ig[0]
    for k, (w_in, w_out, g_in, g_out, use_out) in enumerate(words):
        g_final = gaps_after[k - 1] if k else ig[0]
        start_f = k == 0 or sentence_end(g_final)
        start_i = k == 0 or sentence_end(g_in)
        start_o = k == 0 or sentence_end(g_out)
        w = w_in
        if use_out and w_out != w_in and _case_ok(w_in, w_out):
            if start_o == start_f:
                w = w_out
            else:
                res.decisions.append(Decision("case", w_in, w_out, False, "case follows rejected punct"))
        elif use_out and w_out != w_in:
            res.decisions.append(Decision("case", w_in, w_out, False, "inner case change"))
        if start_f and w[:1].islower() and w_in[:1].isupper():
            w = w_in  # never lowercase a sentence start
        if start_f and not start_i and w[:1].islower() and plain(w):
            w = w[:1].upper() + w[1:]
        text += w + gaps_after[k]
    res.text = text
    # If the model went off the rails (rewrote a large part, appended commentary),
    # don't trust any of its edits.
    n_bad = sum(len(d.src.split()) + len(d.dst.split()) for d in res.decisions
                if not d.accepted and d.op in ("subst", "insert", "delete"))
    if n_bad > max(6, 0.4 * n_in):
        res.decisions.append(Decision("bail", "", "", False, f"{n_bad} words in rejected edits"))
        res.text = inp
    return res


def strip_echo(before: str | None, out: str) -> str:
    """Sentence mode: small models often repeat the read-only "Before:" context in
    their answer. If the output starts with (most of) the context, cut it off."""
    if not before:
        return out
    bw = [norm_word(w) for w in tokenize(before)[0]]
    ow, og = tokenize(out)
    if not bw or len(ow) <= len(bw) // 2:
        return out
    head = [norm_word(w) for w in ow[: len(bw) + 3]]
    matched_last = -1
    n_match = 0
    for blk in align(bw, head):
        if blk.op == "equal":
            n_match += 1
            matched_last = blk.j1
    if n_match < max(2, int(0.7 * len(bw))) or matched_last + 1 >= len(ow):
        return out
    # rebuild the text from the word after the last matched context word
    k = matched_last + 1
    pos = 0
    for idx in range(k):  # find character offset of word k
        pos = out.index(ow[idx], pos) + len(ow[idx])
    return out[pos:].lstrip(" .,;:!?")


# ----------------------------------------------------------------------------
# Edits-only format is lines "wrong -> right", or NONE.

def parse_edits(raw: str) -> list[tuple[str, str]]:
    edits = []
    for line in raw.strip().splitlines():
        line = line.strip().lstrip("-*•0123456789.) ").strip()
        if not line or line.upper().startswith("NONE"):
            continue
        for arrow in ("->", "→", "=>"):
            if arrow in line:
                a, b = line.split(arrow, 1)
                a, b = a.strip().strip('"“”`'), b.strip().strip('"“”`')
                if a:
                    edits.append((a, b))
                break
    return edits


def _find_span(text: str, needle: str, ci: bool) -> int:
    hay = text.lower() if ci else text
    nd = needle.lower() if ci else needle
    start = 0
    while True:
        k = hay.find(nd, start)
        if k < 0:
            return -1
        before_ok = k == 0 or not _is_word_char(text[k - 1]) or not _is_word_char(needle[0])
        end = k + len(nd)
        after_ok = end >= len(text) or not _is_word_char(text[end]) or not _is_word_char(needle[-1])
        if before_ok and after_ok:
            return k
        start = k + 1


def apply_edits(text: str, edits: list[tuple[str, str]]) -> tuple[str, int]:
    applied = 0
    for a, b in edits:
        k = _find_span(text, a, False)
        if k < 0:
            k = _find_span(text, a, True)
        if k < 0:
            continue
        text = text[:k] + b + text[k + len(a):]
        applied += 1
    return text, applied


if __name__ == "__main__":
    tests = [
        ("I think there would be a microwave at the clubhouse, right, and bulls and spoons and things.",
         "I think there would be a microwave at the clubhouse, right? And bowls and spoons and things?"),
        ("We need bulls for the soup.", "We need bowls for the stew."),
        ("I'll be there in an our.", "I'll be there in an hour."),
        ("I am going to the jim.", "I'm going to the gym."),
        ("I won't go.", "I want go."),
        ("Call me at 6:45, okay.", "Call me at 6:45, okay?"),
        ("We can meet every day.", "We can meet everyday."),
        ("Bring the PDF tomorrow.", "Please bring the pdf to me tomorrow."),
    ]
    for a, b in tests:
        r = guard(a, b)
        print(r.text)
        for d in r.decisions:
            print("   ", d)

"""Shared helpers for the reference scripts (stdlib only)."""
import re

SP = ".,?!;:…"


def strip_asr(text):
    """Remove ASR sentence punctuation from word edges (Kotlin: TextProcessing.splitWords), drop empty tokens."""
    out = []
    for tok in text.split():
        t = tok.strip(SP) if not re.fullmatch(r"\.\d.*", tok) else tok.rstrip(SP)
        if t:
            out.append(t)
    return out


def words_of(text):
    return [w.strip(".,?!;:…").lower() for w in text.split() if w.strip(".,?!;:…")]


def read_inputs(path):
    with open(path, encoding="utf8") as f:
        return [l.rstrip("\n") for l in f if l.strip()]

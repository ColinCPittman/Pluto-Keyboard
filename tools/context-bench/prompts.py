"""Prompts for the context-model benchmark.

Two output formats:
  rewrite : the model repeats the text with corrections applied.
  edits   : the model lists only corrections ("wrong -> right" lines, or NONE).

Both share the same user-message format so the app can switch formats without
changing anything else:
    Before: <previous sentence(s), read-only context>   (optional)
    Text: <text to correct>
The system prompt + few-shot turns form a constant prefix whose KV state can be
computed once and reused (only "Text: ..." + the answer cost per request).
"""

SYSTEM_COMMON = (
    "You proofread voice dictation. The text came from speech recognition, so it can contain "
    "words that sound like the intended word but are wrong in context (for example \"meat\" "
    "for \"meet\", \"flower\" for \"flour\"), and questions that end with a period instead of "
    "a question mark.\n"
    "Fix ONLY those two kinds of mistakes. Do not rephrase, do not add or remove words, and "
    "do not change slang, names, numbers or style. If the text is already fine, leave it unchanged. "
    "\"Before:\" is earlier text for context only; never correct or repeat it."
)

SYSTEM_REWRITE = SYSTEM_COMMON + "\nReply with the corrected Text only."

SYSTEM_EDITS = SYSTEM_COMMON + (
    "\nReply with one correction per line in the form: wrong words -> corrected words. "
    "Copy the wrong words exactly as they appear in Text, including punctuation. "
    "For a missing question mark, write the last word with its period -> the word with a question mark. "
    "If nothing needs fixing, reply NONE."
)

# (before, text, corrected, edits)
FEWSHOT = [
    (None,
     "The plain was delayed, so we'll land around nine.",
     "The plane was delayed, so we'll land around nine.",
     ["plain -> plane"]),
    (None,
     "Gonna grab lunch with Sam, want anything.",
     "Gonna grab lunch with Sam, want anything?",
     ["anything. -> anything?"]),
    (None,
     "I already sent the invoice to Dave this morning.",
     "I already sent the invoice to Dave this morning.",
     []),
    ("The baby finally fell asleep.",
     "Can you keep your voice down, she's a really lite sleeper.",
     "Can you keep your voice down? She's a really light sleeper.",
     ["down, she's -> down? She's", "lite -> light"]),
    (None,
     "The dog hurt its paw, so we're going to the vet at four.",
     "The dog hurt its paw, so we're going to the vet at four.",
     []),
    ("We're out of coffee.",
     "I'll stop by the store on my way home, we'll sea what they have.",
     "I'll stop by the store on my way home, we'll see what they have.",
     ["sea -> see"]),
]


SYSTEM_COMMON_V2 = (
    "You correct speech-to-text mistakes in dictated messages.\n"
    "Speech recognition sometimes writes a word that sounds the same as the intended word but makes "
    "no sense in the sentence (like meat/meet, flower/flour, their/there, right/write). Replace such "
    "words with the word the speaker meant. If a sentence is a question but ends with a period, end it "
    "with a question mark.\n"
    "Change nothing else: keep every other word, the word order, names, numbers, slang and style. "
    "\"Before:\" is earlier text, given only as context; do not repeat it."
)

FEWSHOT_V2 = [
    (None,
     "The plain was delayed, so we'll land around nine.",
     "The plane was delayed, so we'll land around nine.",
     ["plain -> plane"]),
    (None,
     "Gonna grab lunch with Sam, want anything.",
     "Gonna grab lunch with Sam, want anything?",
     ["anything. -> anything?"]),
    ("The baby finally fell asleep.",
     "Can you keep your voice down, she's a really lite sleeper.",
     "Can you keep your voice down? She's a really light sleeper.",
     ["down, she's -> down? She's", "lite -> light"]),
    (None,
     "I already sent the invoice to Dave this morning.",
     "I already sent the invoice to Dave this morning.",
     []),
    ("We're leaving for the campsite at six.",
     "Did you pack the tent, and the sleeping bags and the steaks.",
     "Did you pack the tent? And the sleeping bags and the stakes?",
     ["tent, and -> tent? And", "steaks. -> stakes?"]),
    (None,
     "I can't decide witch shirt to where to the wedding.",
     "I can't decide which shirt to wear to the wedding.",
     ["witch -> which", "where -> wear"]),
    (None,
     "The dog hurt its paw, so we're going to the vet at four.",
     "The dog hurt its paw, so we're going to the vet at four.",
     []),
    ("We're out of coffee.",
     "I'll stop by the store on my way home, we'll sea what they have.",
     "I'll stop by the store on my way home, we'll see what they have.",
     ["sea -> see"]),
]

VERSION = "v2"


def _system(fmt: str) -> str:
    common = SYSTEM_COMMON if VERSION == "v1" else SYSTEM_COMMON_V2
    if fmt == "rewrite":
        return common + "\nReply with the corrected Text only."
    return common + SYSTEM_EDITS[len(SYSTEM_COMMON):]


def _fewshot():
    return FEWSHOT if VERSION == "v1" else FEWSHOT_V2


def user_msg(text: str, before: str | None = None) -> str:
    if before:
        return f"Before: {before}\nText: {text}"
    return f"Text: {text}"


def edits_answer(edits: list[str]) -> str:
    return "\n".join(edits) if edits else "NONE"


def messages(fmt: str, text: str, before: str | None = None, system_mode: str = "system") -> list[dict]:
    """Chat messages for one request. system_mode='user' folds the system prompt into
    the first user turn (for templates without a system role)."""
    system = _system(fmt)
    msgs: list[dict] = []
    if system_mode == "system":
        msgs.append({"role": "system", "content": system})
    for k, (b, t, corrected, edits) in enumerate(_fewshot()):
        u = user_msg(t, b)
        if system_mode == "user" and k == 0:
            u = system + "\n\n" + u
        msgs.append({"role": "user", "content": u})
        msgs.append({"role": "assistant", "content": corrected if fmt == "rewrite" else edits_answer(edits)})
    msgs.append({"role": "user", "content": user_msg(text, before)})
    return msgs

"""
Which Smart corrections features earn their place for a voice model, measured (see README.md).

  python -I ablate.py plan   <work> <name> [options]   the option sets, which are cached, a time estimate
  python -I ablate.py run    <work> <name> [options]   runs the missing option sets, then writes the report
  python -I ablate.py report <work> <name> [options]   the report from cached runs only

<work> holds testset-<name>.jsonl (bench.py nemotron|parakeet, moonshine-harness bench) with
"expected" per item. The voice model profile (the app's policy for it) is the one <name> starts with
(nemotron, parakeet, moonshine, whisper: "moonshine-small-punct" is moonshine) unless --profile says.
A <name> ending in "-punct" is text the punctuation stage ran on (--punctuation-ran true).

options:
  --out DIR            cache and reports (default <work>/ablate), in <out>/<name>/
  --testset FILE       instead of <work>/testset-<name>.jsonl
  --profile P          nemotron, parakeet, moonshine or whisper
  --punctuation-ran true|false   whether the punctuation stage ran on the test set's text (for the
                       app's policy; default: true for a "-punct" name, else as the app runs it)
  --subset N           a stratified subset of about N items (same items every time)
  --threads N          harness threads (default 4)
  --sets a,b,...       only these option sets (names as 'plan' prints them)
  --no-build           don't run gradlew installDist first (run builds the harness by default)
  --lib, --package-dir, --gemoji, --java-home, --mingw-bin   harness inputs (defaults below)

Every option set runs the context harness once over the test set (one harness at a time; a lock
file in --out keeps two ablate.py runs from overlapping). Results are cached per option set, test
set content and context stage sources, so an interrupted run picks up where it stopped.
"""
import ctypes, datetime, difflib, hashlib, json, os, re, statistics, subprocess, sys, time
from collections import Counter, defaultdict

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import bench  # noqa: E402  (norm and end_mark, so scores match bench.py score)
import marks  # noqa: E402  (marks after the words the model has right, as marks.py reports them)

REPO = os.path.dirname(os.path.dirname(HERE))
DEFAULTS = {
    "lib": "C:/dev/voice-bench-work/libfutoctxlm.dll",
    "package-dir": "C:/dev/models/context-qwen3-0.6b-en",
    "gemoji": os.path.join(REPO, "java", "res", "raw", "gemoji.json"),
    # the harness is built for Java 17 (JAVA_HOME may be an older one)
    "java-home": os.path.expanduser("~/.jdks/azul-17.0.11") if os.path.isdir(os.path.expanduser("~/.jdks/azul-17.0.11")) else os.environ.get("JAVA_HOME", ""),
    "mingw-bin": "C:/msys64/mingw64/bin",
    "harness": os.path.join(REPO, "tools", "context-harness", "build", "install", "context-harness"),
}
MODELS = ["nemotron", "parakeet", "moonshine", "whisper"]
HARNESS_FORMAT = 1  # bump when tools/context-harness/src changes what a run outputs (part of the cache key)

# Settings (SmartCorrectionOptions flags; harness --options names) and the two parts that aren't
# settings: sentenceBreaks (runs whenever any option is on) and lexiconCasing (part of names).
TOP = ["soundalike", "names", "questions", "numbers", "commands", "fillers", "selfcorrections", "quotes", "emoji"]
SUB = ["sentencebreaks", "lexiconcasing"]
FEATURES = TOP + SUB


def flags_of(spec):
    """The options a --options spec gives (as the harness parses it), canonical: lexiconCasing only
    matters with names, and with nothing on the stage doesn't run (the model alone)."""
    parts = [p.strip().lower() for p in spec.split(",") if p.strip()]
    on = {f: False for f in TOP}
    on.update({"sentencebreaks": True, "lexiconcasing": True})
    pos = {p for p in parts if not p.startswith("-")}
    if "all" in pos:
        on.update({f: True for f in TOP})
    if "default" in pos:
        on.update({"soundalike": True, "names": True, "questions": True})
    for p in pos:
        if p in TOP:
            on[p] = True
    for p in parts:
        if p.startswith("-"):
            on[p[1:]] = False
    if not on["names"]:
        on["lexiconcasing"] = False
    if not any(on[f] for f in TOP):
        return None
    return on


def spec_of(flags):
    on = [f for f in TOP if flags[f]]
    off = [f for f in SUB if not flags[f] and (f != "lexiconcasing" or flags["names"])]
    return ",".join(on + ["-" + f for f in off])


def set_args(s):
    """An option set as harness arguments."""
    spec, profile = s
    if spec is None:
        return "(the model alone)"
    out = f"--options {spec}"
    if profile:
        prof, _, punct = profile.partition(":punct=")
        out += f" --profile {prof}" + (f" --punctuation-ran {punct}" if punct else "")
    return out


def option_sets(model):
    """name -> (spec or None for the model alone, profile or None), and per feature the pair of sets
    whose difference is the feature 'alone' and 'left out of all'."""
    sets = {"model": (None, None), "all": ("all", None),
            "policy-default": ("default", model), "policy-all": ("all", model)}
    pairs = {}

    def add(flags):
        if flags is None:
            return "model"
        spec = spec_of(flags)
        for name, (s, p) in sets.items():
            if p is None and s is not None and flags_of(s) == flags:
                return name
        name = "set:" + spec
        sets[name] = (spec, None)
        return name

    for f in FEATURES:
        if f in TOP:
            # the feature on its own (names with its lexicon casing, as the setting does)
            on = flags_of(f + ",-sentencebreaks")
            alone = (add(on), "model")
        elif f == "lexiconcasing":
            alone = (add(flags_of("names,-sentencebreaks")), add(flags_of("names,-sentencebreaks,-lexiconcasing")))
        else:  # sentence breaks only run with another option on: questions is the host
            alone = (add(flags_of("questions")), add(flags_of("questions,-sentencebreaks")))
        loo = ("all", add(flags_of("all,-" + f)))
        pairs[f] = {"alone": alone, "loo": loo}
    # readable names
    rename = {}
    for f in FEATURES:
        on, off = pairs[f]["alone"]
        if on.startswith("set:") and on not in rename:
            rename[on] = "questions+sentencebreaks" if f == "sentencebreaks" else "only-" + f
        if off.startswith("set:") and off not in rename:
            rename[off] = "only-" + f.replace("lexiconcasing", "names") + "-no-" + ("lexiconcasing" if f == "lexiconcasing" else f)
        lo = pairs[f]["loo"][1]
        if lo.startswith("set:") and lo not in rename:
            rename[lo] = "all-minus-" + f
    sets = {rename.get(k, k): v for k, v in sets.items()}
    for f in FEATURES:
        pairs[f] = {k: tuple(rename.get(x, x) for x in v) for k, v in pairs[f].items()}
    return sets, pairs


# ---------------------------------------------------------------- scoring

def strict_norm(s, cat=""):
    """Tokens with case and punctuation kept, and otherwise bench.norm's equivalences (quote styles,
    "$1,200" and "$1200", "one-on-one" and "one on one", small numbers as words or digits)."""
    if hasattr(bench, "strict_norm"):  # bench.py's own, once it has one
        return bench.strict_norm(s, cat)
    s = s.replace("\n", " ¶ ").replace("“", '"').replace("”", '"').replace("’", "'").replace("‘", "'")
    s = re.sub(r"(?<=\d),(?=\d{3}\b)", "", s)
    s = re.sub(r"(?<=[^\W\d_])-(?=[^\W\d_])", " ", s)
    toks = re.findall(r"¶|\d+(?:[.:]\d+)*|[^\W\d_]+(?:'[^\W\d_]+)*|[^\w\s]", s)
    small = bench.DIGITS if cat == "number" else bench.SMALL
    return [small.get(t.lower(), t) if t.lower() in small and (t.islower() or t[:1].isupper() and t[1:].islower()) else t for t in toks]


def is_strict(item):
    return bool(item.get("strict"))


def checks(item, text):
    """aspect -> right?, for the aspects that apply to the item: words (bench.py's comparison), end
    (the end mark, where the expected text has one) and strict (case and punctuation exact, for
    items marked "strict")."""
    c, exp = item["cat"], item["expected"]
    # bench.right also accepts the item's "alt" texts (set 2)
    out = {"words": bench.right(text, item)}
    if bench.end_mark(exp):
        out["end"] = bench.end_mark(text) == bench.end_mark(exp)
    st = item.get("strict")
    if isinstance(st, str) and st:
        # set 2 names what it checks ("case", "end", "end+comma", "numbers"), as bench.py scores it
        out["strict"] = bench.right(text, item, st)
    elif st:
        out["strict"] = strict_norm(text, c) == strict_norm(exp, c)
    return out


def punct_line(item):
    """Lines whose expected text is punctuated and capitalized as Pluto should type it: those that
    end in ".", "?" or "!", except quotes, whose marks are Smart corrections' quote format (as marks.py)."""
    return bool(bench.end_mark(item["expected"])) and item["cat"] != "quote"


def case_errors(text, exp):
    """Words the text has right (aligned with the expected text) but in another case."""
    def words(s):
        return [w for w in (t.strip(marks.QUOTES + ",.?!;:") for t in s.split()) if w]
    a, b = words(text), words(exp)
    sm = difflib.SequenceMatcher(a=[w.lower() for w in a], b=[w.lower() for w in b], autojunk=False)
    return sum(a[blk.a + k] != b[blk.b + k] for blk in sm.get_matching_blocks() for k in range(blk.size))


def counts(item, text):
    """aspect -> mistakes: case = words the text has right in another case than the expected text
    (every line: the bench's expected texts are cased as Pluto should type them, even those that
    leave punctuation out); marks = on punctuated lines (punct_line), commas, stops and question
    marks after the words the text has right that differ from the expected text (the end mark is
    the 'end' check)."""
    exp = item["expected"]
    out = {"case": case_errors(text, exp)}
    if punct_line(item):
        out["marks"] = sum(1 for hm, em, last in marks.aligned_marks(text, exp) if not last and hm != em)
    return out


FILLERS = {"um", "uh", "er", "ah", "hmm", "erm", "uhm", "mm"}
MARKERS = ["sorry", "i mean", "no wait", "wait no", "actually", "scratch that", "or rather", "make that", "no no"]
EMOJI = re.compile("[\U0001F000-\U0001FAFF\u2600-\u27BF]")
SPOKEN = {"comma", "period", "full", "stop", "question", "mark", "exclamation", "colon", "semicolon", "new", "line", "paragraph", "dash", "hyphen", "quote", "unquote"}


def unfixable_end(item, model_text):
    """The expected end mark is missing and no feature adds one (only "." -> "?" and "," / ";" -> "."
    are fixes), so this end-mark check counts against no feature."""
    exp_mark = bench.end_mark(item["expected"])
    if not exp_mark or bench.end_mark(model_text):
        return False
    return model_text.rstrip().rstrip('"')[-1:] not in (",", ";")


def error_types(item, model_text):
    """The kinds of model mistakes in this item that a feature could fix, as far as the comparison
    can see them (a casing mistake is only visible in a strict item)."""
    c, exp = item["cat"], item["expected"]
    ck = checks(item, model_text)
    types = set()
    if feature_of_cat(c):
        types.add(feature_of_cat(c))
    wi, we = bench.norm(model_text, c), bench.norm(exp, c)
    if not ck["words"]:
        ops = difflib.SequenceMatcher(a=wi, b=we, autojunk=False).get_opcodes()
        for tag, i1, i2, j1, j2 in ops:
            a, b = wi[i1:i2], we[j1:j2]
            if tag == "replace" and all(re.fullmatch(r"[a-z']+", w) for w in a + b) and not (set(a) & (FILLERS | SPOKEN)):
                types.add("soundalike")
            if any(re.search(r"\d", w) for w in b) and not any(re.search(r"\d", w) for w in a):
                types.add("numbers")
            if "¶" in b or (tag in ("replace", "delete") and set(a) & SPOKEN):
                types.add("commands")
            if tag == "delete" and (set(a) & FILLERS or any(i > 0 and wi[i] == wi[i - 1] for i in range(i1, i2))):
                types.add("fillers")
            if '"' in b:
                types.add("quotes")
        low = " ".join(wi)
        if any(re.search(r"\b" + m + r"\b", low) for m in MARKERS) and len(we) < len(wi):
            types.add("selfcorrections")
        if EMOJI.search(exp) and not EMOJI.search(model_text):
            types.add("emoji")
    if ck.get("end") is False:
        # the question fix turns a final "." into "?" (and a tag-question comma into "?"); the end
        # fix turns a final "," or ";" into "." No feature adds a missing end mark (unfixable_end).
        last = model_text.rstrip().rstrip('"')[-1:]
        if bench.end_mark(exp) == "?" and last == ".":
            types.add("questions")
        if bench.end_mark(exp) == "." and last in (",", ";"):
            types.add("questions")
        if bench.end_mark(exp) == "?" and re.search(r",\s*$", model_text.rstrip()):
            types.add("questions")
    if punct_line(item):
        for hm, em, last in marks.aligned_marks(model_text, exp):
            if em == "?" and hm in (".", ","):
                types.add("questions")  # the question fix checks every ".", and tag-question commas
            if not last and hm in (".", "?", "!") and em not in (".", "?", "!"):
                types.add("sentencebreaks")  # a sentence break the expected text doesn't have
    if case_errors(model_text, exp):
        types.update({"names", "lexiconcasing"})
    if ck.get("strict") is False:
        a, b = strict_norm(model_text, c), strict_norm(exp, c)
        aw, bw = [t for t in a if t[0].isalnum()], [t for t in b if t[0].isalnum()]
        if aw != bw and [t.lower() for t in aw] == [t.lower() for t in bw]:
            types.update({"names", "lexiconcasing"})  # the words are right but not their case
        # the question fix checks every ".", not only the last one (and tag-question commas)
        for tag_, i1, i2, j1, j2 in difflib.SequenceMatcher(a=a, b=b, autojunk=False).get_opcodes():
            sa, sb = a[i1:i2], b[j1:j2]
            if tag_ == "replace" and "?" in sb and "?" not in sa and ("." in sa or "," in sa) and \
                    [t.lower() for t in sa if t[0].isalnum()] == [t.lower() for t in sb if t[0].isalnum()]:
                types.add("questions")
        breaks = lambda s: len(re.findall(r"[.!?] +\S", s.strip()))  # noqa: E731
        if breaks(model_text) > breaks(exp):
            types.add("sentencebreaks")
    if item.get("personal"):
        types.add("names")
    return types


# features whose edits are only case or punctuation (invisible to the words check; questions shows in the end mark)
CASE_PUNCT = {"names", "lexiconcasing", "sentencebreaks"}

# categories (bench items) that are about one feature, and back
FEATURE_CATS_REV = {"selfcorrection": "selfcorrections", "command": "commands", "quote": "quotes", "number": "numbers",
                    "filler": "fillers", "soundalike": "soundalike", "names": "names", "casing": "lexiconcasing",
                    "punctuation": "sentencebreaks", "question": "questions", "emoji": "emoji"}
FEATURE_CATS = {f: c for c, f in FEATURE_CATS_REV.items()}
FEATURE_CATS.update({"lexiconcasing": "casing", "questions": "question", "sentencebreaks": "sentence-break"})

# set 2's categories ("_ctl" ones are controls, about no feature). Commas, short dictations, end marks
# and greetings are about no feature either: no Smart correction adds a comma or a missing end mark.
SET2_CATS = {"casing": "lexiconcasing", "casing_pause": "lexiconcasing", "punct_q": "questions",
             "punct_qflat": "questions", "punct_whq": "questions", "punct_tag": "questions",
             "punct_multi": "sentencebreaks", "punct_multipause": "sentencebreaks", "punct_onesent": "sentencebreaks"}


def feature_of_cat(c):
    """The feature a bench category is about, or None (controls and the categories no feature fixes)."""
    if c.endswith("_ctl"):
        return None
    return SET2_CATS.get(c) or FEATURE_CATS_REV.get(c)


# ---------------------------------------------------------------- running

def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    with open(path, "rb") as f:
        return sha(f.read())


def load_jsonl(path):
    with open(path, encoding="utf8") as f:
        return [json.loads(l) for l in f if l.strip()]


def stratified(items, n):
    by = defaultdict(list)
    for it in items:
        by[it["cat"]].append(it)
    total = len(items)
    pick = set()
    for c, its in by.items():
        k = max(2, round(n * len(its) / total)) if n < total else len(its)
        k = min(k, len(its))
        # evenly spaced, so both voices and the whole range of a category are covered
        for j in range(k):
            pick.add(its[(j * len(its)) // k]["id"])
    return [it for it in items if it["id"] in pick]


def pid_alive(pid):
    if os.name == "nt":
        k = ctypes.windll.kernel32
        h = k.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not h:
            return False
        code = ctypes.c_ulong()
        k.GetExitCodeProcess(h, ctypes.byref(code))
        k.CloseHandle(h)
        return code.value == 259  # STILL_ACTIVE
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


class Lock:
    """One harness at a time for every ablate.py using the same --out."""

    def __init__(self, path):
        self.path = path

    def __enter__(self):
        waited = False
        while True:
            try:
                fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                os.write(fd, str(os.getpid()).encode())
                os.close(fd)
                return self
            except FileExistsError:
                try:
                    with open(self.path) as f:
                        pid = int(f.read().strip() or 0)
                except (OSError, ValueError):
                    pid = 0
                if pid and not pid_alive(pid):
                    os.remove(self.path)  # left by a run that died
                    continue
                if not waited:
                    print(f"waiting for the harness lock {self.path} (pid {pid})", flush=True)
                    waited = True
                time.sleep(5)

    def __exit__(self, *a):
        try:
            os.remove(self.path)
        except OSError:
            pass


def harness_stale(install):
    """Why the harness build doesn't match the sources, or None: its copy of the voice model policy
    differs from engine/, so run --profile would use an old policy."""
    shared = os.path.join(REPO, "voiceinput-shared", "src", "main", "java", "org", "futo", "voiceinput", "shared")
    build = os.path.dirname(os.path.dirname(install))  # .../context-harness/build
    for n in ("VoiceModelProfile.kt", "SmartFeaturePolicy.kt"):
        copy, src = os.path.join(build, "policy-src", n), os.path.join(shared, "engine", n)
        if not os.path.isfile(copy) or file_sha(copy) != file_sha(src):
            return f"build/policy-src/{n} differs from engine/{n}"
    return None


def sources_sha():
    """What decides the harness's results: the context stage, the policy and their resources. The
    harness's own Main.kt counts through HARNESS_FORMAT (bump it when a change there changes results),
    so a new harness flag doesn't throw away hours of cached runs."""
    shared = os.path.join(REPO, "voiceinput-shared", "src", "main", "java", "org", "futo", "voiceinput", "shared")
    files = [os.path.join(shared, "engine", n) for n in ("VoiceModelProfile.kt", "SmartFeaturePolicy.kt")]
    for d in (os.path.join(shared, "context"), os.path.join(REPO, "voiceinput-shared", "src", "main", "resources")):
        for root, _, names in os.walk(d):
            files += [os.path.join(root, n) for n in names]
    h = hashlib.sha256(f"harness format {HARNESS_FORMAT}\0".encode())
    for f in sorted(files):
        with open(f, "rb") as fh:
            # line endings differ between checkouts
            h.update(os.path.relpath(f, REPO).replace("\\", "/").encode() + b"\0" + fh.read().replace(b"\r\n", b"\n") + b"\0")
    return h.hexdigest()


def build_harness(a):
    """gradlew installDist (up-to-date checks make this quick when nothing changed), so the runs
    use the current context stage and policy."""
    gradlew = os.path.join(REPO, "gradlew.bat" if os.name == "nt" else "gradlew")
    env = dict(os.environ, JAVA_HOME=a["java-home"])
    print("building the context harness", flush=True)
    r = subprocess.run([gradlew, "-p", os.path.join(REPO, "tools", "context-harness"), "installDist", "-q"],
                       cwd=REPO, env=env, capture_output=True, text=True, errors="replace")
    if r.returncode != 0:
        sys.exit(f"harness build failed:\n{(r.stdout + r.stderr)[-3000:]}")


class Runner:
    def __init__(self, a):
        self.a, self.model = a, a["name"]
        self.profile = a["profile"]
        self.out = a["out"]
        self.dir = os.path.join(self.out, a["name"])
        os.makedirs(self.dir, exist_ok=True)
        ts = a["testset"]
        bench.need_file(ts, "testset")
        items = load_jsonl(ts)
        # "strict" may be marked in the testset or only in items.json
        meta = os.path.join(os.path.dirname(ts), "items.json")
        if os.path.isfile(meta):
            with open(meta, encoding="utf8") as f:
                strict = {d["id"] for d in json.load(f) if d.get("strict")}
            for it in items:
                if it["id"] in strict:
                    it["strict"] = True
        for it in items:
            if "expected" not in it:
                sys.exit(f"{it['id']} in {ts} has no 'expected'")
        if a.get("subset"):
            items = stratified(items, a["subset"])
        self.items = items
        data = "".join(json.dumps(it, ensure_ascii=False, sort_keys=True) + "\n" for it in items).encode("utf8")
        self.ts_hash = sha(data)[:12]
        self.testset = os.path.join(self.dir, f"testset-{self.ts_hash}.jsonl")
        if not os.path.isfile(self.testset):
            with open(self.testset, "wb") as f:
                f.write(data)
        h = a["harness"]
        self.jar = os.path.join(h, "lib", "context-harness.jar")
        bench.need_file(self.jar, "context harness (gradlew -p tools/context-harness installDist)")
        stale = harness_stale(h)
        if stale:
            sys.exit(f"the context harness build is stale ({stale}); rebuild: gradlew -p tools/context-harness installDist")
        bench.need_file(a["lib"], "libfutoctxlm")
        pkg = a["package-dir"]
        if not os.path.isdir(pkg):
            sys.exit(f"context package not found: {pkg}")
        pkg_id = sorted((n, os.path.getsize(os.path.join(pkg, n))) for n in os.listdir(pkg))
        gem = a["gemoji"] if a["gemoji"] and os.path.isfile(a["gemoji"]) else None
        self.gemoji = gem
        # the harness's sources (a rebuilt jar differs byte for byte even when they don't), the native
        # library, the context package and the emoji names
        self.env_key = sha(json.dumps([sources_sha(), file_sha(a["lib"]), pkg_id, file_sha(gem) if gem else None]).encode())[:8]
        # the app's policy: the profile, and whether the punctuation stage ran on the text
        pr = self.profile if a["punctuation-ran"] is None else f"{self.profile}:punct={a['punctuation-ran']}"
        self.sets, self.pairs = option_sets(pr)

    def key(self, name):
        spec, profile = self.sets[name]
        return sha(json.dumps([spec, profile, self.ts_hash, self.env_key]).encode())[:12]

    def path(self, name):
        return os.path.join(self.dir, f"{name}-{self.key(name)}.jsonl")

    def cached(self, name):
        return self.sets[name][0] is None or os.path.isfile(self.path(name))

    def results(self, name):
        if self.sets[name][0] is None:
            return {it["id"]: {"id": it["id"], "guarded": it["input"], "phone_ms": [], "secs": 0.0} for it in self.items}
        return {d["id"]: d for d in load_jsonl(self.path(name))}

    def meta(self, name):
        p = self.path(name)[:-6] + ".meta.json"
        if os.path.isfile(p):
            with open(p, encoding="utf8") as f:
                return json.load(f)
        return {}

    def run(self, name):
        spec, profile = self.sets[name]
        a = self.a
        final, part, log = self.path(name), self.path(name) + ".part", self.path(name)[:-6] + ".log"
        java = os.path.join(a["java-home"], "bin", "java.exe" if os.name == "nt" else "java")
        bench.need_file(java, "java (--java-home)")
        cmd = [java, "-Xmx2g", "-cp", os.path.join(a["harness"], "lib", "*"), "MainKt", "run",
               "--lib", a["lib"], "--package-dir", a["package-dir"], "--testset", self.testset, "--out", part,
               "--threads", str(a["threads"]), "--options", spec]
        if profile:
            prof, _, punct = profile.partition(":punct=")
            cmd += ["--profile", prof] + (["--punctuation-ran", punct] if punct else [])
        if self.gemoji:
            cmd += ["--gemoji", self.gemoji]
        env = dict(os.environ)
        env["PATH"] = a["mingw-bin"] + os.pathsep + env.get("PATH", "")
        env["JAVA_HOME"] = a["java-home"]
        with Lock(os.path.join(self.out, "harness.lock")), open(log, "w", encoding="utf8") as lf:
            t0 = time.time()
            print(f"{name}: {' '.join(cmd[cmd.index('--options'):])} ({len(self.items)} items)", flush=True)
            p = subprocess.Popen(cmd, stdout=lf, stderr=subprocess.STDOUT, env=env)
            try:
                rc = p.wait()
            except KeyboardInterrupt:
                p.terminate()  # only our own child
                p.wait()
                raise
        wall = time.time() - t0
        if rc != 0:
            with open(log, encoding="utf8", errors="replace") as f:
                tail = f.read()[-2000:]
            sys.exit(f"{name}: harness exited with {rc} (log {log})\n{tail}")
        got = load_jsonl(part)
        if len(got) != len(self.items):
            sys.exit(f"{name}: {len(got)} results for {len(self.items)} items (log {log})")
        with open(log, encoding="utf8", errors="replace") as f:
            opts = next((l.strip() for l in f if l.startswith("options: ")), "")
        if not profile and policy_flags(opts) != flags_of(spec):
            sys.exit(f"{name}: the harness ran {policy_flags(opts)}, not {flags_of(spec)} (log {log})")
        with open(final[:-6] + ".meta.json", "w", encoding="utf8") as f:
            json.dump({"set": name, "spec": spec, "profile": profile, "flags": policy_flags(opts), "wall_s": round(wall, 1),
                       "testset": self.a["testset"], "testset_hash": self.ts_hash, "env_key": self.env_key,
                       "threads": a["threads"], "items": len(self.items), "when": datetime.datetime.now().isoformat(timespec="seconds")}, f, indent=1)
        os.replace(part, final)
        print(f"  {wall:.0f} s", flush=True)
        return wall


# ---------------------------------------------------------------- report

def pct(xs, q):
    if not xs:
        return 0.0
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(q * len(xs)))]


def cost(res):
    calls = [len(d.get("phone_ms", [])) for d in res.values()]
    secs = [d.get("secs", 0.0) * 1000 for d in res.values()]
    lm = [sum(d.get("phone_ms", [])) for d in res.values()]
    n = max(1, len(calls))
    return {"calls_mean": sum(calls) / n, "calls_p90": pct(calls, 0.9), "calls_total": sum(calls),
            "ms_median": statistics.median(secs) if secs else 0.0, "ms_p90": pct(secs, 0.9),
            "lm_ms_median": statistics.median(lm) if lm else 0.0}


def transitions(items, A, B):
    """(fixed, broken) checks going from outputs A to outputs B, as [(id, cat, aspect)]: a check
    turning right / wrong, or fewer / more mark or case mistakes on a punctuated line."""
    fixed, broken = [], []
    for it in items:
        a, b = A[it["id"]]["guarded"], B[it["id"]]["guarded"]
        ca, cb = checks(it, a), checks(it, b)
        for asp in ca:
            if not ca[asp] and cb[asp]:
                fixed.append((it["id"], it["cat"], asp))
            elif ca[asp] and not cb[asp]:
                broken.append((it["id"], it["cat"], asp))
        na, nb = counts(it, a), counts(it, b)
        for asp in na:
            if nb[asp] < na[asp]:
                fixed.append((it["id"], it["cat"], asp))
            elif nb[asp] > na[asp]:
                broken.append((it["id"], it["cat"], asp))
    return fixed, broken


def distance(a, b):
    """Word edit distance."""
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def changes(items, A, B):
    """Items whose output B differs from A without any check flipping, split into those whose words
    got closer to the expected text, further from it, or neither (case or punctuation only)."""
    closer, further, other = [], [], []
    for it in items:
        a, b = A[it["id"]]["guarded"], B[it["id"]]["guarded"]
        if a == b or checks(it, a) != checks(it, b) or counts(it, a) != counts(it, b):
            continue
        c = it["cat"]
        e = bench.norm(it["expected"], c)
        da, db = distance(bench.norm(a, c), e), distance(bench.norm(b, c), e)
        (closer if db < da else further if db > da else other).append(it["id"])
    return {"closer": closer, "further": further, "other": other}


def ids(lst, most=25):
    """Checks as ids, or per category and check when there are many (the json has them all)."""
    if len(lst) <= most:
        return ", ".join(map(tag, lst))
    return "by category: " + ", ".join(f"{c} {k}" for c, k in Counter(t[1] + ("" if t[2] == "words" else f" ({t[2]})") for t in lst).most_common())


def tag(t):
    return t[0] if t[2] == "words" else f"{t[0]}({t[2]})"


def policy_flags(line):
    """SmartCorrectionOptions(...) as the harness logged it -> {feature: on}."""
    m = {"soundAlike": "soundalike", "names": "names", "questions": "questions", "numbers": "numbers", "commands": "commands",
         "fillers": "fillers", "selfCorrections": "selfcorrections", "quotes": "quotes", "emoji": "emoji",
         "sentenceBreaks": "sentencebreaks", "lexiconCasing": "lexiconcasing"}
    out = {}
    for k, f in m.items():
        g = re.search(r"\b" + k + r"=(true|false)", line)
        if g:
            out[f] = g[1] == "true"
    if out and not any(out.get(f) for f in TOP):
        out = {f: False for f in FEATURES}
    elif out and not out.get("names"):
        out["lexiconcasing"] = False
    return out


def report(r):
    items, model = r.items, r.model
    missing = [n for n in r.sets if not r.cached(n)]
    have = {n: r.results(n) for n in r.sets if r.cached(n)}
    base = have["model"]
    n_items = len(items)
    cats = Counter(it["cat"] for it in items)
    strict_n = sum(is_strict(it) for it in items)
    n_checks = Counter()
    for it in items:
        for asp in list(checks(it, it["expected"])) + list(counts(it, it["expected"])):
            n_checks[asp] += 1
    n_punct = n_checks.get("marks", 0)

    # errors of each type the model makes, as the comparison sees them
    err_items = defaultdict(list)
    no_end = [it["id"] for it in items if unfixable_end(it, it["input"])]
    for it in items:
        ck = checks(it, it["input"])
        if all(ck.values()):
            continue
        for t in error_types(it, it["input"]):
            err_items[t].append(it["id"])

    sets_summary = {}
    for n, res in have.items():
        ok = Counter()
        for it in items:
            ck = checks(it, res[it["id"]]["guarded"])
            for asp, v in ck.items():
                ok[asp] += v
            ok["all"] += all(ck.values())
            for asp, v in counts(it, res[it["id"]]["guarded"]).items():
                ok[asp + "_mistakes"] += v
        spec, prof = r.sets[n]
        sets_summary[n] = {"spec": spec, "profile": prof, "right": dict(ok), **cost(res), "wall_s": r.meta(n).get("wall_s")}

    pol = {k: r.meta(k).get("flags", {}) for k in ("policy-default", "policy-all") if k in have}

    feats = {}
    for f in FEATURES:
        p = r.pairs[f]
        row = {"pairs": p}
        for mode in ("alone", "loo"):
            on, off = p[mode]
            if on in have and off in have:
                # alone: against the model alone (or the host without f); loo: all against all minus f
                fx, br = transitions(items, have[off], have[on])
                ca, cb = cost(have[on]), cost(have[off])
                row[mode] = {"fixed": fx, "broken": br, "changed": changes(items, have[off], have[on]),
                             "calls": ca["calls_mean"] - cb["calls_mean"], "ms_median": ca["ms_median"] - cb["ms_median"]}
        fixed = {(t[0], t[2]) for m in ("alone", "loo") if m in row for t in row[m]["fixed"]}
        broken = {(t[0], t[2]) for m in ("alone", "loo") if m in row for t in row[m]["broken"]}
        errs = len(err_items.get(f, []))
        ch = {k: sorted({i for m in ("alone", "loo") if m in row for i in row[m]["changed"][k]}) for k in ("closer", "further", "other")}
        n_ch = sum(map(len, ch.values()))
        if "alone" not in row and "loo" not in row:
            verdict = "NOT RUN"
        elif not fixed and not broken:
            verdict = "UNTESTED" if errs == 0 else "NO EFFECT"
            if f in CASE_PUNCT and not strict_n and not (n_punct if f == "sentencebreaks" else n_checks.get("case")):
                verdict = "UNTESTED"  # its edits are case and punctuation, which nothing here checks
            if n_ch:
                verdict += f" (changed {n_ch} items, no check flipped: {len(ch['closer'])} closer, {len(ch['further'])} further, {len(ch['other'])} case/punct only)"
        elif not broken:
            verdict = "KEEP"
        elif not fixed:
            verdict = "HARMFUL"
        elif len(fixed) > len(broken):
            verdict = "KEEP (mixed)"
        elif len(fixed) < len(broken):
            verdict = "HARMFUL (mixed)"
        else:
            verdict = "MIXED"
        row.update({"verdict": verdict, "n_fixed": len(fixed), "n_broken": len(broken), "errors_in_set": errs, "changed_no_flip": ch,
                    "error_items": err_items.get(f, []),
                    "category_items": [it["id"] for it in items if feature_of_cat(it["cat"]) == f],
                    "policy_default": pol.get("policy-default", {}).get(f), "policy_all": pol.get("policy-all", {}).get(f)})
        feats[f] = row

    # which items needed which feature
    per_item = defaultdict(lambda: defaultdict(list))
    for f, row in feats.items():
        for mode, key in (("alone", "fixed_alone"), ("loo", "needed_in_all"), ("alone", "broken_alone"), ("loo", "breaks_in_all")):
            if mode not in row:
                continue
            lst = row[mode]["fixed" if key in ("fixed_alone", "needed_in_all") else "broken"]
            for t in lst:
                per_item[t[0]][key].append(f + ("" if t[2] == "words" else f"({t[2]})"))

    policy_vs = {}
    for pn in ("policy-default", "policy-all"):
        if pn in have:
            fx, br = transitions(items, base, have[pn])
            policy_vs[pn] = {"vs_model": {"fixed": fx, "broken": br}}
            if "all" in have:
                fx2, br2 = transitions(items, have[pn], have["all"])
                policy_vs[pn]["all_vs_policy"] = {"fixed": fx2, "broken": br2}

    js = {"model": model, "testset": r.a["testset"], "subset": r.a.get("subset"), "items": n_items, "categories": dict(cats),
          "profile": r.profile, "punctuation_ran": r.a["punctuation-ran"], "strict_items": strict_n, "checks": dict(n_checks), "missing_sets": missing, "sets": sets_summary,
          "features": {f: {k: v for k, v in row.items()} for f, row in feats.items()},
          "items_table": {k: dict(v) for k, v in per_item.items()}, "policy": policy_vs, "errors_in_set": dict(err_items),
          "unfixable_end_marks": no_end,
          "generated": datetime.datetime.now().isoformat(timespec="seconds")}
    suffix = f"-sub{r.a['subset']}" if r.a.get("subset") else ""
    jp = os.path.join(r.dir, f"report{suffix}.json")
    with open(jp, "w", encoding="utf8") as f:
        json.dump(js, f, ensure_ascii=False, indent=1)

    # ---- markdown
    L = []
    w = L.append
    w(f"# Smart corrections ablation: {model}\n")
    pr = r.a["punctuation-ran"]
    w(f"Voice model profile `{r.profile}`; the punctuation stage " + ("ran on this text" if pr == "true" else "did not run on this text" if pr == "false" else "as the app runs it for this profile") + " (for the app's policy sets).")
    w(f"Test set `{r.a['testset']}`" + (f", stratified subset of {n_items} items" if r.a.get("subset") else f", {n_items} items") +
      f" ({', '.join(f'{c} {k}' for c, k in sorted(cats.items()))}). Strict items (case and punctuation exact): {strict_n}.")
    w(f"Checks per item: words (bench.py score), end mark where the expected text has one ({n_checks.get('end', 0)} items)"
      + (f", strict ({n_checks['strict']})" if strict_n else ", no strict check (no item is marked strict)")
      + f"; case mistakes on the words the model has right (every line), and mark mistakes after them on {n_punct} punctuated lines (marks.py's alignment: lines that end in a mark, quotes left out)."
      + ("" if n_punct or strict_n else " Nothing checks punctuation inside a line, so sentence-break fixes are invisible."))
    w(f"End marks the model left out, which no feature adds (the question fix only turns a final \".\" into \"?\", the end fix a final \",\" into \".\"): "
      f"{len(no_end)} items{' (' + ', '.join(no_end) + ')' if no_end else ''}. They count against no feature.")
    w(f"Generated {js['generated']}." + (f" **Not run yet:** {', '.join(missing)}." if missing else ""))
    w("")
    w("Cost: LM calls per dictation are counted exactly (each LmScorer call that ran the model) and don't depend on the machine; "
      "times are wall clock on a desktop shared with other jobs, so treat them as rough and compare LM calls.\n")

    w("## Verdicts\n")
    w("Fixed / broken count checks (item × words/end/strict) that the feature turns right / wrong, and punctuated lines whose "
      "mark or case mistakes it makes fewer / more (item × marks/case), on its own "
      "(against the model alone) or as part of everything (all against all without it). UNTESTED: no item in the set has "
      "the kind of mistake the feature fixes and it changed nothing, so this set says nothing about whether it is needed.\n")
    w("| feature | verdict | fixed | broken | model mistakes of its kind in set | app runs it (default / all) | LM calls alone | LM calls saved without it |")
    w("|---|---|---|---|---|---|---|---|")
    for f, row in feats.items():
        yn = lambda v: "?" if v is None else ("yes" if v else "no")  # noqa: E731
        ca = row.get("alone", {}).get("calls")
        cl = row.get("loo", {}).get("calls")
        verdict = row["verdict"]
        flag = ""
        if row["policy_all"] is False and verdict.startswith("KEEP"):
            flag = " ⚠ app drops it"
        if row["policy_all"] is False and verdict == "UNTESTED":
            flag = " ⚠ dropped without evidence" if not row["category_items"] else \
                f" (app drops it; the model got its {len(row['category_items'])} {FEATURE_CATS[f]} items right itself)"
        if row["policy_all"] and verdict.startswith("HARMFUL"):
            flag = " ⚠ app runs it"
        w(f"| {f} | **{verdict}**{flag} | {row['n_fixed']} | {row['n_broken']} | {row['errors_in_set']} | "
          f"{yn(row['policy_default'])} / {yn(row['policy_all'])} | {'' if ca is None else f'{ca:+.2f}'} | {'' if cl is None else f'{cl:+.2f}'} |")
    w("")
    w("questions covers three fixes: a final \".\" -> \"?\", tag questions (\"..., right,\" -> \"right?\") and a final \",\" -> \".\" "
      "(ContextRescorer: all three run only with questions on). names, lexiconcasing and sentencebreaks edit case and punctuation, "
      "which the strict check and the case and mark counts see" + ("." if strict_n or n_punct else "; this set has no strict items or punctuated lines, so sentencebreaks is UNTESTED whatever it changes.") + "\n")
    w("Alone: " + "; ".join(f"{f} = {r.pairs[f]['alone'][0]} vs {r.pairs[f]['alone'][1]}" for f in SUB) +
      " (the others: the feature alone vs the model alone). Without it: all vs all-minus-<feature>.\n")

    w("## Option sets\n")
    w("| set | options | right (all checks) | words | end | strict | mark / case mistakes | LM calls / dictation (mean, p90) | ms median / p90 | run wall s |")
    w("|---|---|---|---|---|---|---|---|---|---|")
    for n, s in sets_summary.items():
        rt = s["right"]
        opt = set_args((s["spec"], s["profile"]))
        w(f"| {n} | `{opt}` | {rt.get('all', 0)}/{n_items} | {rt.get('words', 0)} | {rt.get('end', '')} | {rt.get('strict', '')} | "
          f"{rt.get('marks_mistakes', '')} / {rt.get('case_mistakes', '')} | "
          f"{s['calls_mean']:.2f}, {s['calls_p90']} | {s['ms_median']:.0f} / {s['ms_p90']:.0f} | {s['wall_s'] or ''} |")
    w("")

    if policy_vs:
        w("## The app's policy\n")
        for pn, v in policy_vs.items():
            opts = pol.get(pn, {})
            w(f"**{pn}** runs: {', '.join(f for f in FEATURES if opts.get(f)) or '(nothing)'}; off: {', '.join(f for f in FEATURES if opts and not opts.get(f))}.")
            fx, br = v["vs_model"]["fixed"], v["vs_model"]["broken"]
            w(f"- against the model alone: fixed {len(fx)} ({ids(fx)}), broken {len(br)} ({ids(br)})")
            if "all_vs_policy" in v:
                fx, br = v["all_vs_policy"]["fixed"], v["all_vs_policy"]["broken"]
                w(f"- everything on instead: fixes {len(fx)} more ({ids(fx)}), breaks {len(br)} ({ids(br)})")
        w("")

    w("## Per feature and category\n")
    for f, row in feats.items():
        if "alone" not in row and "loo" not in row:
            continue
        w(f"### {f}: {row['verdict']}\n")
        w(f"Model mistakes of its kind in the set: {row['errors_in_set']}" + (f" ({', '.join(row['error_items'][:20])}{' ...' if len(row['error_items']) > 20 else ''})" if row["error_items"] else "") + ".\n")
        w("| category | n | alone fixed | alone broken | in all fixed | in all broken | net (distinct checks) |")
        w("|---|---|---|---|---|---|---|")
        tot = [0, 0, 0, 0]
        for c in sorted(cats):
            cell = []
            vals = []
            for mode in ("alone", "loo"):
                for k in ("fixed", "broken"):
                    lst = [t for t in row.get(mode, {}).get(k, []) if t[1] == c]
                    vals.append(len(lst))
                    cell.append(f"{len(lst)}" + (f" ({', '.join(map(tag, lst))})" if lst else ""))
            if not any(vals):
                continue
            for i in range(4):
                tot[i] += vals[i]
            fx = {(t[0], t[2]) for m in ("alone", "loo") for t in row.get(m, {}).get("fixed", []) if t[1] == c}
            br = {(t[0], t[2]) for m in ("alone", "loo") for t in row.get(m, {}).get("broken", []) if t[1] == c}
            w(f"| {c} | {cats[c]} | {' | '.join(cell)} | {len(fx) - len(br):+d} |")
        w(f"| **total** | {n_items} | {tot[0]} | {tot[1]} | {tot[2]} | {tot[3]} | {row['n_fixed'] - row['n_broken']:+d} |")
        ch = row["changed_no_flip"]
        if any(ch.values()):
            w("\nChanged without flipping a check: " + "; ".join(f"{k} {len(v)} ({', '.join(v)})" for k, v in ch.items() if v) + ".")
        w("")

    w("## Which items needed which feature\n")
    w("Items some feature changed. Fixed alone: the feature on its own makes it right. Needed in all: right with everything on, "
      "wrong without that feature. A suffix names the check when it isn't the words.\n")
    w("| item | cat | model | all | policy (all) | fixed alone by | needed in all | broken alone by | breaks it in all |")
    w("|---|---|---|---|---|---|---|---|---|")
    okm = lambda n, it: ("ok" if all(checks(it, have[n][it["id"]]["guarded"]).values()) else "x") if n in have else ""  # noqa: E731
    for it in items:
        d = per_item.get(it["id"])
        if not d:
            continue
        w(f"| {it['id']} | {it['cat']} | {okm('model', it)} | {okm('all', it)} | {okm('policy-all', it)} | "
          + " | ".join(", ".join(d.get(k, [])) for k in ("fixed_alone", "needed_in_all", "broken_alone", "breaks_in_all")) + " |")
    w("")
    mp = os.path.join(r.dir, f"report{suffix}.md")
    with open(mp, "w", encoding="utf8") as f:
        f.write("\n".join(L))
    print(f"report: {mp}\n        {jp}")
    return js


# ---------------------------------------------------------------- main

def parse(argv):
    if len(argv) < 3 or argv[0] not in ("plan", "run", "report"):
        sys.exit(__doc__.strip())
    a = dict(DEFAULTS)
    name = argv[2]
    a.update({"cmd": argv[0], "work": argv[1], "name": name, "threads": 4, "subset": None, "sets": None, "no-build": False,
              "profile": next((m for m in MODELS if name.startswith(m)), None),
              "punctuation-ran": "true" if name.endswith("-punct") else None})
    a["out"] = os.path.join(argv[1], "ablate")
    a["testset"] = os.path.join(argv[1], f"testset-{argv[2]}.jsonl")
    i = 3
    while i < len(argv):
        k = argv[i]
        if k == "--no-build":
            a["no-build"] = True
            i += 1
            continue
        if not k.startswith("--") or i + 1 >= len(argv):
            sys.exit(f"bad argument {k}\n\n" + __doc__.strip())
        v = argv[i + 1]
        k = k[2:]
        if k == "no-build":
            a[k] = True
            i += 1
            continue
        if k in ("threads", "subset"):
            v = int(v)
        elif k == "sets":
            v = [s.strip() for s in v.split(",") if s.strip()]
        elif k == "punctuation-ran" and v not in ("true", "false"):
            sys.exit("--punctuation-ran true|false")
        elif k == "profile" and v not in MODELS:
            sys.exit(f"--profile {'|'.join(MODELS)}")
        elif k not in a:
            sys.exit(f"unknown option --{k}")
        a[k] = v
        i += 2
    if a["profile"] is None:
        sys.exit(f"no voice model profile for '{name}': name one with --profile {'|'.join(MODELS)}")
    if a["threads"] > 4:
        print("note: more than 4 threads on a shared machine")
    return a


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    a = parse(sys.argv[1:])
    if a["cmd"] == "run" and not a["no-build"]:
        build_harness(a)
    r = Runner(a)
    names = [n for n in r.sets if r.sets[n][0] is not None]
    if a["sets"]:
        bad = [s for s in a["sets"] if s not in r.sets]
        if bad:
            sys.exit(f"unknown sets {bad}; known: {', '.join(names)}")
        names = [n for n in names if n in a["sets"]]
    todo = [n for n in names if not r.cached(n)]
    if a["cmd"] == "plan":
        print(f"{len(r.items)} items (testset {r.ts_hash}), {len(names)} harness runs, {len(todo)} not cached; cache {r.dir}")
        walls = [r.meta(n).get("wall_s") for n in names if r.cached(n) and r.meta(n).get("wall_s")]
        for n in names:
            print(f"  {'cached ' if r.cached(n) else 'to run '} {n:32s} {set_args(r.sets[n])}"
                  + (f"  ({r.meta(n).get('wall_s')} s)" if r.cached(n) else ""))
        if walls:
            print(f"estimate for the rest: about {len(todo) * statistics.median(walls) / 60:.0f} min (median of {len(walls)} runs so far)")
        return
    if a["cmd"] == "run":
        t0 = time.time()
        for k, n in enumerate(todo):
            print(f"[{k + 1}/{len(todo)}] ", end="")
            r.run(n)
        if todo:
            print(f"ran {len(todo)} sets in {(time.time() - t0) / 60:.1f} min")
    report(r)


if __name__ == "__main__":
    main()

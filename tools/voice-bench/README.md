# voice-bench

Runs a voice model and Smart corrections together on synthetic speech, so a change to either can be
measured on the same few hundred dictations.

`sentences.py` holds 170 lines (self-corrections, ordinary sentences that must stay as they are,
commands, quotes, numbers, fillers, plain sentences and questions, longer dictation), each with
what Pluto should type. Every line is spoken by two Windows voices (David and Zira, which come with
Windows), with "|" as a pause of about a second. Results are compared across runs, so change a line
only to fix a mistake in it, and say so in the commit.

```
python -I tools/voice-bench/bench.py items <work>
powershell -File tools/voice-bench/tts.ps1 <work>/items.json <work>/wav
python -I tools/voice-bench/bench.py nemotron|parakeet <work> <transcribe-cli> <model.gguf>
tools/context-harness/build/install/context-harness/bin/context-harness run --lib <libfutoctxlm.dll> \
    --package-dir <context package> --testset <work>/testset-<model>.jsonl --out <work>/out-<model>.jsonl --options all
python -I tools/voice-bench/bench.py score <work> <model>
```

Paths may contain spaces (quote them). `tts.ps1` skips WAVs that already exist, so delete `<work>/wav`
after changing a line. `transcribe-cli` is transcribe.cpp's command-line tool (build the submodule
`voiceinput-shared/src/main/cpp/transcribe.cpp` with CMake on the desktop); the run stops at the first
clip it fails on, rather than counting an empty transcript as a model mistake.

`nemotron` runs the streaming model the way the phone does (160 ms chunks), groups its word pieces
into words like `transcribe_jni.cpp` and splits at pauses like `PauseSegments`, so the context stage
sees what it would see on the phone. `parakeet` runs the model on the whole recording and uses its
word timestamps, with each word ending at its last token that isn't punctuation (Parakeet emits the
"?" or "." after a word once speech resumes, so its word row would end after the pause), as
`transcribe_jni.cpp` does.

`score` compares words, not case or most punctuation, and reports per category how many lines the
model alone got right, how many Smart corrections got right, and how many it fixed or broke. Line
breaks and quote marks count as words, and so do the marks inside numbers (3:30, $45.99), so the
number has to be written the way Pluto writes it. Spellings that are equally right count the same:
"one-on-one" and "one on one", "$1,200" and "$1200", and a number below ten as a word or a digit
("six tables"). Outside the number category, numbers up to twelve can be either. The end mark
columns count only lines whose expected text ends in ".", "?" or "!" (plain sentences, quotes and
long dictation). The failures go to `<work>/fails-<model>.json`.

## Set 2

`sentences.py` `ITEMS2` is a second, larger set (586 lines) with one category per Smart corrections
feature and controls where the fix must not fire: sound-alikes (`soundalike`, `soundalike_ctl`),
capitals (`casing`, `casing_pause`, `casing_ctl`), punctuation (`punct_*`: questions said with a
rising and with a flat voice, tag questions, commas, end marks, greetings that end in a comma,
sentence breaks with and without pauses, one sentence with a pause inside), fillers, numbers, commands,
names from the personal dictionary and emoji by name. Many lines come from
`docs/VOICE_MODEL_EVIDENCE.md` (tests T1-T9), including the sound-alike mistakes the models made on set 1.
It goes in its own work folder, and its ids have their own prefixes.

```
python -I tools/voice-bench/bench.py items2 <work2>
powershell -File tools/voice-bench/tts.ps1 <work2>/items.json <work2>/wav
python -I tools/voice-bench/bench.py nemotron|parakeet <work2> <transcribe-cli> <model.gguf>
python -I tools/voice-bench/bench.py score <work2> <model> --alone
```

Every line is spoken four ways: David (`d`), Zira (`z`), Mark (`m`, a OneCore voice that comes with
Windows 10 and 11) and Zira at 115% speed (`f`). The OneCore David and Zira give the same audio as the
Desktop ones, so they add nothing. What is said has no punctuation, except the `punct_q` lines, which end
in "?" so the voice goes up; `punct_qflat` says the same lines flat. TTS says "um" and "uh" as words,
unlike people, so the filler lines say more about the Filler fix than about how a model treats a real "um".

A line may carry `alt` (other texts that are equally right), `personal` (personal-dictionary words,
which the context harness reads per line) and `strict` (set per category in `STRICT2`). Word scoring
ignores case and punctuation, so `strict` says what else must match: `case`, `end` (. ? ! wherever
they are), `comma`, and `numbers` (a number word and its digits differ, for lines where a number
must stay a word). `score` then adds strict columns (model, +smart, helped, hurt) for the categories
that have them, and a strict line counts as right only when its words and its strict parts match.
For the emoji lines the context harness needs `--gemoji java/res/raw/gemoji.json`. `score --alone`
scores the model's own text without context-harness output (written to `fails-<model>-alone.json`).
Set 1 scores the same as before: it has no `alt` or `strict`, and the other additions to the
comparison ("p.m." and "pm", an emoji as its own word) don't occur in it.

`nemotron` and `parakeet` pass `--threads 4` to transcribe-cli (change it with `--threads N`), and write
each clip to `testset-<model>.jsonl.part` as they go, so a stopped run carries on where it was.

The context harness needs the desktop `libfutoctxlm` (see `voiceinput-shared/src/main/cpp/CMakeLists.txt`;
with MinGW, add `-DCMAKE_CXX_FLAGS=-fpermissive`) and an extracted context model package.

## Moonshine and the punctuation model

Moonshine runs in `tools/moonshine-harness` (the app's own engine code), and so does the punctuation
stage, which can also run after Nemotron or Parakeet to see whether it would help them.

```
./gradlew -p tools/moonshine-harness installDist
tools/moonshine-harness/build/install/moonshine-harness/bin/moonshine-harness bench <work> <moonshine package dir> moonshine-small
tools/moonshine-harness/build/install/moonshine-harness/bin/moonshine-harness punct <work> <punctuation package dir> moonshine-small moonshine-small-punct
tools/moonshine-harness/build/install/moonshine-harness/bin/moonshine-harness punct <work> <punctuation package dir> nemotron nemotron-punct
python -I tools/voice-bench/marks.py report <work> moonshine-small moonshine-small-punct nemotron nemotron-punct
python -I tools/voice-bench/marks.py alone <work> moonshine-small-punct
python -I tools/voice-bench/bench.py score <work> moonshine-small-punct-alone
```

The package dirs are the extracted zips from the models release (`PlutoLinks.MODELS_PAGE`).
`bench` feeds each recording in 100 ms blocks like `AudioRecognizer`, with the app's default
Moonshine settings (refresh 500 ms, live text on), a fresh VAD per clip and the model kept loaded, and
writes the joined text as `input` and each utterance as a segment, like `MoonshineStreamingSession`.
`punct` runs the stage like `PunctuationModels.restore()`: the segments when there is more than one,
otherwise the text, and it keeps `segments` as they were, since the context stage gets the engine's
own segments. The restorer replaces the model's periods and commas with its own, keeps its "?" and
"!", and always ends the text with a mark. The app runs the stage only after Moonshine
(`SmartFeaturePolicy.punctuationStage`). The output testsets go through the context harness like the
others; give it the profile the app would use (`--profile moonshine`).

`marks.py alone` writes `testset-<model>-alone.jsonl` and `out-<model>-alone.jsonl` with the text
unchanged, so `bench.py score` scores the model alone. `marks.py report` prints the word error rate
(after `bench.py`'s normalization) and end marks, commas and question marks on the lines whose
expected text is punctuated, counting commas only on words the model got right.

On Windows, ONNX Runtime crashes in `msvcp140.dll` when the JDK's older copy is loaded first, so run
the harness with `JAVA_HOME` set to a copy of a JDK 17 whose `bin/msvcp140.dll`, `vcruntime140.dll`
and `vcruntime140_1.dll` are replaced by the ones in `C:\Windows\System32` (building works with any JDK 17).

## Which features a model needs: ablate.py

`ablate.py` decides per voice model, from measurements, which Smart corrections features earn their
place. For a model's test set (with `expected`) it runs the context harness with: everything on,
the app's policy for the model (`--profile`, with the default and with all settings), each feature on
its own, and everything but one feature. The model alone needs no run (its text is the input).

```
python -I tools/voice-bench/ablate.py plan   <work> <name> [--subset 120]   # sets, cache state, time estimate
python -I tools/voice-bench/ablate.py run    <work> <name> [--subset 120] [--out DIR] [--threads 4]
python -I tools/voice-bench/ablate.py report <work> <name> [--subset 120]   # from the cache only
```

`<name>` picks `<work>/testset-<name>.jsonl` (or `--testset`), and the profile is the model it starts
with (`moonshine-small-punct` is moonshine; or `--profile`). A name ending in `-punct` is text the
punctuation stage ran on, so the app's policy is computed with `--punctuation-ran true` (the harness
flag; `--punctuation-ran true|false` overrides), which keeps the sentence-break veto for Nemotron or
Parakeet with the punctuation stage. Without it the policy is what the app does for the profile.

It writes `<out>/<name>/report[-subN].md` and `.json` (default out: `<work>/ablate`): per feature and
category the checks it fixed and broke (with item ids), alone and as part of everything; what each
option set gets right and costs; which item needed which feature; and a verdict per feature:

- KEEP: it fixes at least one item (alone, or it is needed among the others) and breaks fewer;
- HARMFUL: it breaks more than it fixes;
- NO EFFECT: it changed nothing although the model makes that kind of mistake in the set;
- UNTESTED: it changed nothing and the set has no mistake of its kind, so the set says nothing
  about it. Never read UNTESTED as "not needed"; add items that have the mistake.

The report flags a feature the app's policy drops although it fixes items, or drops without
evidence (UNTESTED), and one the app runs although it is harmful.

Each item is checked on its words (as `score`), its end mark where the expected text has one (so
question marks count), and, for items marked `"strict": true` (in the test set or `items.json`), on
case and punctuation exactly (`bench.strict_norm` if bench.py has one, else the same equivalences as
`norm` with case and marks kept). It also counts case mistakes on the words the model has right
(every line: the expected texts are cased as Pluto should type them, even those without punctuation)
and, on punctuated lines (those that end in a mark, quotes left out, as `marks.py`), mark mistakes
after those words (commas, inner stops, question marks) with `marks.aligned_marks`; a feature that
makes fewer or more of them fixes or breaks that line. `sentencebreaks` only edits punctuation, so
with neither strict items nor punctuated lines it comes out UNTESTED whatever it changes. "Mistakes of its kind" are found from the model's text against the expected text (a changed
word, a missing number, a filler or correction phrase left in, a "." where a "?" belongs, a sentence
break the expected text doesn't have, a word in the wrong case, ...), or from the item's category. A
changed output that flips nothing is listed too (closer to or further from the expected words, or
case and punctuation only).

`questions` is three fixes in ContextRescorer: "." -> "?" at a sentence end, tag questions ("...,
right," -> "right?") and a final "," or ";" -> ".". No feature adds a missing end mark, so the report
lists those items apart and counts them against no feature.

The two parts that aren't settings are measured too: `lexiconcasing` (names with and without it) and
`sentencebreaks` (only runs with another option on; measured as questions with and without it). The
harness takes `-name` entries in `--options` for this (`all,-fillers`). `names` without a personal
dictionary only does casing (items may carry `"personal"`). `emoji` uses `java/res/raw/gemoji.json`.

Cost is LM calls per dictation, which are exact and repeat run to run. Times are wall clock and
on a busy machine vary several times over between runs of the same items (one Parakeet dictation
took 831 s for one LM call while the machine stalled), so compare LM calls. `run` builds the harness
first (`--no-build` skips it), and the harness build always copies the current voice model profiles
and policy (a stale copy made `--profile` run an old policy). Runs are cached per option set, test
set content, the context stage's sources, libfutoctxlm and the context package, so an interrupted run
resumes and a change to the context stage runs everything again (a change to the harness's Main.kt
that changes results needs `HARNESS_FORMAT` in ablate.py bumped). Only one harness runs at a time
(a lock file in the output directory).

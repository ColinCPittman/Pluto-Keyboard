# Smart corrections stress test (all options on)

Tools: `mk.py` (compact batch_NN.txt -> batch_NN.jsonl), `prun.py` (parallel harness runs + scoring),
`phone.jsonl` (owner's phone regressions), `asr_01.jsonl` (real Moonshine output of TTS speech).
Results: `D:/FUTO-models/context/harness/stress/<round>/`.

A "false edit" is any change to a control (a correct sentence). Items whose expected result was
debatable were turned into positives with the unchanged text as an allowed alternative (noted below).

## Round 1 (batches 01-02, 895 items)

Baseline on the existing sets: zero false edits, as reported.

batch_01 (476 items, 404 controls): 19 false edits. batch_02 (419, 356 controls): 6.

| Category | Examples | Fix |
| --- | --- | --- |
| Quote after a reporting verb + filler | "I went, uh, to the doctor" -> `I went "Uh, to the doctor"`; "She said, um, that she'd be late" | Fillers only open a quote after quotative "was like" / "goes"; after "said", "went", "told him" they are hesitations (removed by the filler option) |
| "sorry" as an apology | "Thanks, sorry, gotta run." -> "Gotta run."; "tonight, sorry, my car broke down" -> "I my car broke down." | One-word markers need a pause after them; a repair that starts a phrase ("my car", "the store") replaces a phrase; not the whole sentence before "sorry" |
| "rather" as an adverb | "a long, rather boring meeting" -> "a boring meeting" | (same: pause after one-word markers) |
| "oh wait" before a clause | "Let me check, oh wait, the store is closed" -> "Let me the store is closed" | (same: phrase-start repair) |
| Emphatic repeats | "Got it, got it, thanks" -> "Got it, thanks" | A pause between the copies and after the second one = said twice on purpose |
| Reduplications | "the mahi mahi" -> "the mahi" | Not a stutter when the word is not a common word, or is a name ("Bora Bora") |
| "uh huh" | "Uh huh, that's what I thought." -> "Huh, ..." | "uh"/"um" + "huh"/"oh"/"hmm" without a pause is one interjection |
| Indirect questions | "I was wondering if you're free tonight." -> "?" ; "Guess who I ran into today." -> "?" | "I wonder", "I was wondering", "I'm not sure", "guess", "imagine" ... need the command margin |
| Song title lowercased | "the song Yesterday" -> "yesterday" | Mid-sentence capitals are only lowercased at an ASR utterance start |
| Two commands in a row | "Dear Sarah comma new paragraph" kept "comma" | Command stage runs again on its own output |
| Sound-alike swaps | pare->pair, noise->news, brake->break, fare->fire, pool->poll, kite->cat, minus->menace, Sew->So | Round 2 |

Owner's phone bugs (from the coordinator), fixed in the same commit:
- `open-quote x-ray, close-quote, for some reason` was not converted: hyphenated command phrases now
  match (rules and guard); the unhyphenated Moonshine segments version works too ("Close quote. For
  some reason," -> `" for some reason`), and a dangling final comma becomes a period.
- "x-ray" -> "Sarah" (a learned name): names only replace words that look like a misheard name (never a
  compound, a lowercase word, or a common word); the guard enforces it.

## Round 2 (batches 03-04, 854 items)

batch_03 (409, 338 controls): 15 false edits, batch_04 (445, 362 controls): 13.

| Category | Examples | Fix |
| --- | --- | --- |
| Sound-alike swaps (the biggest class) | beat->bought, batter->butter, lock->like, cap->cop, lit->late, sus->sauce, Bet->Bit, Same->Sam, For->We're, ref->roof, plain->plan, stationery->stationary, cut->gut, fan->phone, sign->sun, large->lark | Context must win: gain minus the replacement's prior advantage (LM log-prob in neutral frames) must beat 3.1 for exact homophones and 7.0 for near sound-alikes; homophone groups from Windows TTS pronunciations of the lexicon's pairs |
| Question marks | "Serve with rice or bread." -> "?", "How nice of you." -> "?", "Kinda tired, might skip the gym." -> "?" | Sentences not shaped like a question (no verb like are/can, question word, subject, or tag) need the command margin; exclamations "How X of you", "What a" too |
| Number | "Preheat the oven to three fifty." -> "3:50" | Times without am/pm only after at/by/until/... |
| Self-correction | "Add two cups of sugar, sorry, one cup." -> "Add two cups of one cup." | A number repair replaces a number |
| Command | "Once upon a time comma" not converted | "time", "trial", "waiting", ... only make "period" a noun |
| Case | "wifi" -> "WiFi" | Only first-letter capitalization |
| Line break | "comma new paragraph Thank you" -> "thank you" | No lowercasing after a line break |
| to/too | "We're going too." -> "to." | No swap to a word that can't end a clause before a pause |

After round 2: batches 01-04 and all existing sets: zero false edits except "We're going too." (fixed
above). Recall: testset 33/34 (s21 "This soap is a little bland" -> "soup" is no longer made: a
near sound-alike with weak evidence), testset_allon 43/44 (al19 "launch" -> "lunch" no longer happens).

## Round 3 (batch 05, 426 items)

4 false edits: fan->phone (gain 11, a near sound-alike whose vowel is clearly different), plus three
judged acceptable on review ("Hmm, not sure." -> "Not sure.", "Er, never mind." -> "Never mind.":
the filler option removing a hesitation; "That's a lot, a lot of money." -> repeat removed) and turned
into positives with both outputs allowed.

Fix: near sound-alikes are only swapped when the one differing sound is one a voice model can confuse
(lax/tense vowels, voicing, ...: "bulls"/"bowls", "pool"/"poll"); a clearly different sound
("fan"/"phone", "kite"/"cat", "noise"/"news") is never swapped. 11,868 confusable pairs.

## Round 4 (batch 06 fresh, 442 items; real ASR set asr_01, 204 items)

batch_06: zero false edits (fresh).

asr_01: 204 sentences spoken by Windows TTS (Zira/David, three speeds, 1 s of trailing silence),
transcribed by Moonshine small streaming (`--mode stream --split pause`), fed with its segments.
Every change was reviewed by hand. Moonshine ends a sentence at nearly every pause ("I was. Um.
Thinking ...", "on Friday No wait. Saturday.", "she was like, um. What are you doing?"), which broke
fillers, self-corrections and quotes. Fixes:
- A filler the ASR made its own sentence may be joined across, the next word lowercased ("The meeting
  is, um... At 2." -> "The meeting is at 2.", "I was. Um. Thinking" -> "I was thinking"); the guard
  allows a filler / self-correction deletion to lowercase the word after it.
- A self-correction repair may start a new sentence after a phrase made for corrections ("no wait",
  "I mean") or when a number replaces a number ("We need three. Actually, four chairs."); digits count
  as numbers ("Meet me at 5. I mean, six.").
- A quote may open with an interjection said on its own ("was like, um. What are you doing?" ->
  `was like "Um, what are you doing?"`, "was like, hey! Can you stay late?"); after "said" only real
  interjections ("um", "oh", "wow"), not "no".
- A colon command lowercases the next word like a comma.
- Not a misfire of the context stage: when the audio ends right after the last word, the streaming
  harness returns an empty final segment (whole utterances lost); 1 s of trailing silence fixes it.

## Round 5 (batch 07 fresh, 500 items; asr_02, 100 items)

batch_07: 12 changed controls. Fixed:
- "Do your homework." / "Have a great holiday." -> "?": "do"/"have" at the start need a subject after
  them to be a question; "The fur coat." / "All set." -> "?": a noun-phrase start needs a verb.
- "To new beginnings." -> "Two": no sound-alike swap into a number word.
- "Q ampersand A" -> "Q & a": one-letter words keep their case.
- Accepted on review (both outputs allowed): "Um, okay." / "Uh, no thanks." / "Hmm, okay." (fillers).
- Not fixed (LM residuals, exact homophones): "a lot of mist" -> "missed" (gain 5.4), "Pare down the
  list" -> "Pair" (gain 10.5, the LM has learned the common misspelling).
- "I know, I know, I should have called." -> repeat of "know, I" removed (found by the unit test):
  no pause allowed inside a repeated copy.

asr_02: all changes reviewed; correct except "dehydrated We're heading" -> "we're": words that start a
clause (we're, that's, it's, ...) are no longer lowercased at an ASR utterance start.

## Round 6 (batches 08-09 fresh, 487 + 501 items)

batch_08: "A cent short." -> "Ascent short." (a sentence's first "A" is not merged with the next word);
"Hi Sam comma running late period" missed ("late" only made "period" a noun by accident: removed);
"the oxford comma" -> "Oxford" accepted.
batch_09: "A big question mark." -> "A big?" ("big", "huge", "little", ... make a two-word command a
noun too); "Who knows when ..." / "How lucky are we." -> "?" accepted; five sentence-initial fillers
accepted.

## Round 7 (batches 10-13 fresh, 502 + 503 + 489 + 505 items)

batch_10: "Pare the apples." -> "Pair"; "How cool." -> "?" (now an exclamation unless "how come",
"how much", ...). batch_11: "Pare it down." -> "Bear"; "Thursday at ten" -> "at 10" and "Where
else." -> "?" accepted. batch_12: "The doe ran." -> "dough"; "At sign or at symbol?" -> "@or" (a
handle/tag/amount symbol is not put before "or", "means", ...); "Asterisk means footnote." -> "* means"
(a spaced symbol needs a word before it); "two dollars" -> "$2" accepted. **batch_13: zero false
edits.**

Changing the candidate choice to prefer exact homophones over near ones ("I new it" -> "knew", not
"know") exposed more exact-homophone swaps in short sentences ("The plain bagel" -> "plane", "Pare the
carrots thin" -> "Pair"). All of the remaining exact-homophone misfires had (almost) nothing before
the word in its sentence: "Pare ...", "The doe ...", "A cent ...", "The plain ...", "By two hundred
..." -> "Buy". At a sentence start the LM only knows which word usually opens a sentence, which the
neutral-frame prior underestimates. Fix: a swap of a word that opens its sentence, or follows only a
sentence-initial "The" / "A" / "My" ..., needs 6.5 more. No existing set has such a fix; four of my
own synthetic positives ("There going to be late." -> "They're", gain 7.8-8.6) are no longer made.

## Final state (stopped at the owner's request after ~7,000 sentences)

Final code, all options on (`D:/FUTO-models/context/harness/stress/r16`, `r17`):
- Every existing set: zero false edits (testset 12/12, extra 12/12, holdout 43/43, allon 56/56,
  allon_probe 21/21, commands 34/34, fillers 24/24, selfcorrections 31/31, names 22/22, numbers 18/18,
  questions 15/15, soundalike 20/20 controls unchanged). Recall: testset 33/34 (was 34/34: s21
  "soap" -> "soup" lost), allon 43/44 (was 42/44: al19 "launch" -> "lunch" fixed), all others unchanged.
- batches 01-10 (4,507 items, 3,824 controls): one false edit left, "a lot of mist" -> "missed"
  (exact homophone, LM gain 5.4).
- batch_13 (fresh, previous code) and batch_14 (fresh, final code, 494 items): zero false edits.
  batch_15 (fresh, final code): 359 of 491 items run before stopping, zero false edits.
- batches 11-12 were only partly re-run with the final code (474/503, 154/489): zero false edits.
- Phone regressions (phone.jsonl): 6/6, 14/14.

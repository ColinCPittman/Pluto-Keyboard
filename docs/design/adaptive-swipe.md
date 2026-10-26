# Adaptive swipe typing (proposal)

Status: proposal only, nothing built yet. Written 2026-10-24.

The typing heatmap learns where each of my taps lands and moves each key's hit area to match. Swipe typing doesn't learn anything like that today. This page looks at why, what a swipe could learn from, and three ways to build it.

## How swipe works today

1. `PointerTracker` and `BatchInputArbiter` (in `java/src/org/futo/inputmethod/keyboard/`) collect the finger path as a list of points (`InputPointers`) and hand it to `InputLogic.onUpdateBatchInput` / `onEndBatchInput`.
2. `WordComposer.setBatchInputPointers` stores the path, and the path goes to the native suggest code as is.
3. The native recognizer (`native/jni/src/suggest/policyimpl/gesture/`, mostly `swipe_weighting.h`) walks the dictionary and scores each word by how close the path comes to each letter's key, how far the path wanders off the straight line between letters, and where the path starts and ends.
4. It gets each key's position from the native `ProximityInfo` through `getKeyCenterXOfKeyIdG`. That returns the "sweet spot" center when touch position correction data exists, and the plain geometric center otherwise.

Things I confirmed in the code:

- **The heatmap never reaches swipes.** The learned offsets are used in two places only. `KeyDetector` uses them to pick the key under a tap, and `WordComposer.applyProcessedEvent` uses `TypingHeatmap.correctTapCoordinates` to move a tap's coordinates before the suggestion engines see them. That second one is skipped when `mIsBatchMode` is set, so swipe paths go to the recognizer untouched.
- **Swipes teach the heatmap nothing.** `TapAligner.align` rejects batch input right away (`BATCH_OR_RESUMED`), and `TypingHeatmap.onWordCommitted` doesn't even make a buffer entry for a swiped word.
- **The sweet spot hook is there but off.** `ProximityInfo.java` can send per-key sweet spot centers to native code, and the swipe scorer would use them. But nothing ever loads `TouchPositionCorrection` data, so it's always off and the recognizer uses plain key centers. Turning it on would also change native tap scoring though, and taps are already corrected by the heatmap, so it can't be switched on as is (they would be corrected twice).
- **Rejections are already noticed, just not learned from.** When I backspace right after a swipe, `InputLogic` deletes the whole word and calls `setRejectedBatchModeSuggestion`. Picking another suggestion after a swipe goes through `onPickSuggestionManually`. Both are good signals that are only used for the dictionary today (`unlearnWord`), not for geometry.

## What a swipe could learn from

The label (which word I meant) has to fit how people actually swipe. Swiping is fast, and by the time I notice a wrong word I'm usually already one or two words further. So I almost never go back and tap another suggestion in the strip. That case is too rare to build on. What actually happens:

- **I keep the word (most of the data).** I swipe "hello", it comes out right, and I keep going. Nearly every swipe is like this, so this is where the learning has to come from, the same way the tap heatmap learns mostly from taps that were already right. The word is only learned once it's a few words old and nothing around it was deleted or edited, so a wrong word I fix a moment later isn't learned as "kept".
- **I delete back a few words and swipe them again (the main fix).** I notice "gone" a word or two late, delete back past it (backspace, or the delete-whole-words modes), and swipe "home" plus the words after it again. The deleted words get paired in order with the new ones, the same way `RecentWordBuffer` already pairs deleted and retyped words for taps, so the old "gone" path is learned as "home". If the pairing doesn't line up cleanly (a different number of words, or words that look nothing alike) it's dropped.
- **I fix the word later.** I tap into the wrong word, or select it, and fix it (deleting it and typing or swiping the right one, a suggestion for that word, or swiping over the selection). This works like `relabel` does for taps, as long as the word is still in the buffer.
- **I pick another suggestion right away.** Rare when swiping fast, but free to use when it happens.

### The path has to agree with the fixed word

A fix only says which word I meant. It doesn't say the swipe was a good attempt at that word. Sometimes I botch a swipe completely, for example I forget to go over to a letter on the other side of the keyboard, and the keyboard rightly gives a different word. If I then delete it and put in the right word, that path must not be learned as the right word. It never went near one of the letters, so learning it would pull keys toward places I never meant, and it would interfere with normal swipes.

So every fixed word (whichever way it was fixed) is checked against the path first, and the whole word is thrown away unless all of these hold.

- **Every letter was visited.** Going through the fixed word's letters in order, the path has to come within about one key of each letter's key, in that order. One letter the path never got near (like the forgotten letter across the keyboard) is enough to throw the word away.
- **The start and end match.** The first point is within about one key of the first letter, and the last point within about one key of the last letter.
- **No extra turns.** Every sharp turn in the path lines up with a letter of the fixed word. A turn toward a key that isn't in the word means I swiped something else.
- **The length is about right.** The path isn't much shorter or much longer than the straight lines between the word's keys (a missed far-away letter usually makes the path much too short).

Only a path that passes all of these counts as "the right word, swiped a bit off", and that's the only kind worth learning. In practice a fixed word that passes is usually one neighbouring letter or a cut corner away from what the recognizer picked, which is exactly the case where shifted keys help. The same checks run on kept words too, as a safety net.

A wrong word I never notice gets learned as if it were right. That hurts less than it sounds. The recognizer picked that word because the path fits it, so the samples mostly agree with what the recognizer already does, and the alignment checks below throw away paths whose start, end or turns are far from the word's keys. Like the heatmap, each sample is also capped so one bad swipe can't move a key much.

What the path tells us, from most to least reliable:

- **Start and end points.** Touch-down and lift-off are the closest thing a swipe has to a tap. Their offset from the first and last letter's key is a clean per-key sample, just like a tap sample.
- **Sharp turns.** Where the path turns hard (say over 60 degrees), I'm usually at or near a letter. With the word known, each turn can be matched to a letter and gives a sample too.
- **Letters I pass through.** For a letter in the middle of a straight run ("o" in "pour"), the path only passes near the key. Its offset is mostly about how I cut corners, not where the key "is" for me. These should be left out of per-key offsets, or used only for the overall corner-cutting style (see option C).

Swipe offsets probably differ from tap offsets. A tap is a deliberate touch aimed at a key, a swipe is a fast moving finger that rounds corners and undershoots edge keys. So swipe statistics should be kept separately from tap statistics, even if they use the same code.

## Privacy

Same rules as the heatmap.

- Store per-key statistics only (`KeyTapStats`, a separate "swipe" profile per layout and orientation). Never store words, paths, or anything tied to text.
- Paths are only held in memory for the few words that can still be corrected, then turned into per-key samples and dropped. Nothing about a path is written to disk.
- Use the same `canLearn` check (no learning in password fields, incognito / no-learning fields, fields without suggestions, or before the phone is unlocked), and ignore input injected over adb.
- Per-word path templates ("this is how I swipe 'because'") would work well but would store words, so they are out.

## Option A: use the tap heatmap for swipes too

Bend the swipe path with the offsets the heatmap has already learned from taps, before the path goes to the recognizer. This is the swipe version of `correctTapCoordinates`. Every point of the path gets moved by a smooth blend of the shifts of the keys near it (keys closer to the point count more), so a path that passes where I usually hit "e" gets pulled toward the stock "e".

- Where: `WordComposer.setBatchInputPointers` (or just before it in `InputLogic`), plus a `correctPath` function next to `HeatmapLayout.correct`.
- No new learning, no native changes, no new data.
- **Effort:** small, about a day with tests.
- **Risk:** tap habits may not match swipe habits, and could make swipes slightly worse. The shifts are small (up to half a key) so it's unlikely to break anything badly, but there's no way to know without trying. It also only helps people who tap enough to train the heatmap.

## Option B: learn swipe offsets from start, end and turns (recommended)

Learn per-key offsets from swipes, kept separately from tap offsets, and use them to bend the path like in option A.

- **Learning.** When a swiped word becomes final (kept for a few words with nothing edited, or replaced by a deleted-and-swiped-again word), match the path to the word's letters. The first point goes with the first letter, the last point with the last letter, and the sharp turns go with the middle letters in order, matched to the nearest letter key ahead. Anything that doesn't line up cleanly is thrown away, like `TapAligner` does, using the agreement checks above (every letter visited in order, start and end match, no extra turns, length about right). The samples go into a separate "swipe" profile of `HeatmapModel`.
- **Labels.** Kept words from the start, plus delete-and-swipe-again pairing through a small in-memory buffer like `RecentWordBuffer` (holding the last few paths). Picked suggestions and later fixes come along for free once the buffer exists.
- **Applying.** The same path bending as option A, but with the swipe profile. The heatmap viewer in settings could show the swipe profile next to the tap one.
- **Effort:** medium, about three to five days. Most of the pieces exist (`KeyTapStats`, `HeatmapModel`, the profile files, the viewer). The new parts are the path aligner, the path bending, and hooking the suggestion pick and backspace events for batch words.
- **Risk:** low to medium. Bad matches are the main risk, and they are handled the same way as for taps (throw away anything unclear, shrink small sample counts toward zero shift, cap the shift at half a key). The bending is a Java-only change and easy to turn off.

## Option C: teach the recognizer itself

Change the native swipe scorer so it takes per-user key positions and a per-user "corner-cutting" tolerance.

- Send swipe-only key centers to native code (a second set of centers in `ProximityInfo`, used only by `SwipeWeighting`, so taps aren't corrected twice).
- Learn one or two global numbers, like how far my path typically passes from middle letters, and use them to scale `keyThreshold` and the line deviation penalty in `swipe_weighting.h`.
- Optionally rescore the recognizer's top candidates in Java using the learned statistics.
- **Effort:** large, one to two weeks. Native changes, a JNI change, native rebuilds for every ABI, and the native code has almost no tests.
- **Risk:** medium to high. The thresholds in `swipe_weighting.h` are hand-tuned and interact in ways that are hard to predict, and a mistake can quietly cull the right word for everyone. It also makes merging upstream FUTO changes to the swipe code harder.

## Recommended first step

Build the learning half of option B in "watch only" mode.

1. Hold the paths of the last few swiped words in memory until each one is final (kept for a few words, or deleted and swiped again).
2. Run the path aligner and add the samples to a separate swipe profile. Don't change the path that goes to the recognizer yet.
3. Show the swipe profile in the heatmap viewer, and log (counts only, never words) how many swipes were aligned or thrown away and why (for example "fixed word had a letter the path never visited"). That shows whether the agreement checks are too strict or too loose before anything changes how swiping behaves.

After a week or two of normal swiping the viewer will show whether my swipe offsets are consistent per key and how different they are from my tap offsets. If they are clearly consistent, turn on the path bending (option B) behind a setting and compare how often I pick another suggestion or re-swipe, before and after. If the swipe and tap offsets turn out to be nearly the same, option A is enough and the swipe profile can be dropped. Option C is only worth it if path bending helps but not enough.

## What was built

Option B, with learning and path bending both on from the start.

- `SwipeAligner` (in `heatmap/SwipeAlignment.kt`) does the agreement checks and makes samples from the start, the end and sharp turns. Unit tests are in `SwipeAlignerTest`.
- Swiped words go into the same recent-word buffer as typed words (with their path kept in memory only), so keeping, deleting and swiping again, and fixing a word later all work the same way as for taps. A swiped word deleted with one backspace while it's still being composed is set aside too, so the next word replaces it.
- Samples go into a separate profile per layout and orientation, with "|swipe" at the end of its name. It's saved in the heatmap file and shows up in the heatmap viewer, marked "swipe".
- `AdaptiveSwipe.bend` moves every point of a new swipe (both the point list and the gesture segments) before the recognizer sees it. So it works with both the neural decoder and the old one.
- The Strength slider has five steps. Each step sets how many swipe points a key needs before its learned offset is trusted (60 at the gentlest, 10 at the strongest), and how much of the offset is applied (half up to 1.4 times, never more than 0.6 key).

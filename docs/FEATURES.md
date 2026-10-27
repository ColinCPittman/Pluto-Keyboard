# Pluto Keyboard features guide

This guide goes through everything Pluto Keyboard adds on top of [FUTO Keyboard](https://github.com/futo-org/android-keyboard), including what each feature does, how to set it up, how it works under the hood, and how to get the most out of it. For the features Pluto shares with FUTO Keyboard, see FUTO's guide (Settings → Help → FUTO Keyboard guide).

Everything here runs on your phone. Pluto has no internet permission, so nothing you type or say ever leaves the device.

The first part of this guide explains each feature in everyday terms. If you want the exact rules, thresholds and side effects behind every setting, they're in [Under the hood](#under-the-hood) at the end.

**Contents**

- [Voice typing](#voice-typing)
  - [Live voice typing with Moonshine](#live-voice-typing-with-moonshine)
  - [Fix punctuation](#fix-punctuation)
  - [Smart corrections for voice](#smart-corrections-for-voice)
  - [Sensitivity dials](#sensitivity-dials)
  - [Advanced voice options](#advanced-voice-options)
  - [Choosing the voice input app](#choosing-the-voice-input-app)
  - [Managing models](#managing-models)
- [Typing](#typing)
  - [Smart corrections for typing](#smart-corrections-for-typing)
  - [The typing corrections list](#the-typing-corrections-list)
  - [Adaptive typing heatmap](#adaptive-typing-heatmap)
  - [Adaptive swipe learning](#adaptive-swipe-learning)
  - [The suggestion menu](#the-suggestion-menu)
  - [Always use middle suggestion](#always-use-middle-suggestion)
  - [Correct words before punctuation](#correct-words-before-punctuation)
- [Your words and names](#your-words-and-names)
- [Privacy](#privacy)
- [Themes](#themes)
- [Starting settings](#starting-settings)
- [Backup and moving from FUTO Keyboard](#backup-and-moving-from-futo-keyboard)
- [Feature tips](#feature-tips)
- [Help](#help)
- [Getting the models](#getting-the-models)
- [Under the hood](#under-the-hood) (the exact rules, thresholds and side effects of every setting)

---

## Voice typing

Voice input happens in a few short steps. Only the first one is required, and each of the others has its own switch.

1. **Speech recognition** turns your voice into words. English uses Moonshine (live), and other languages use FUTO's Whisper (after you finish).
2. **Fix punctuation** takes out the voice model's punctuation (which puts a period at every pause) and punctuates the whole dictation again, so pauses no longer break your sentences apart.
3. **Smart corrections** reads the finished text with a small language model and fixes what only the whole sentence can tell, like misheard words, names, numbers and spoken commands.

Tap the microphone to start. Tap again to finish, or tap **Cancel** to throw the dictation away without typing anything.

You can also hold the microphone button in the suggestion bar and talk, then let go to finish (like a walkie-talkie). It starts listening the moment you touch it (a quick tap still opens the voice window). The voice window stays closed, a soft bubble grows out from the button over the keyboard as you talk, and a small spinner shows on the button while the text is being finished. If you let go before it starts listening, nothing is typed.

### Live voice typing with Moonshine

**What it does.** Words appear while you speak, with no time limit on how long you talk. English only. Other languages keep using Whisper, which types your words after you stop.

**Set it up.** Import a Moonshine voice model under Languages & Models → English → Voice Input Model, then select it. The three sizes trade speed for accuracy.

| Model | Size | Best for |
|---|---|---|
| Small | about 124 MB | Most phones. Recommended. |
| Medium | about 239 MB | Fast, recent phones, for the fewest misheard words. |
| Tiny | about 39 MB | Older or slower phones. Fastest, but misses more words. |

**How it works.** Moonshine runs on ONNX Runtime. A voice activity detector (Silero VAD) splits long dictation into utterances at your pauses, so the model only ever works on a short stretch of audio and dictation can go on indefinitely. After two idle minutes the model is let go, to free up memory.

**Getting the most out of it.**
- Speak in phrases with natural pauses. Pauses tell the later steps where sentences and commands begin.
- If live text feels laggy, try Tiny, or turn down **Live text refresh** under Advanced options.

### Fix punctuation

**What it does.** After you stop speaking, it removes Moonshine's periods, commas and other punctuation and works out where your sentences really end, plus the commas and capital letters. It keeps Moonshine's question marks and exclamation marks (Moonshine heard your tone of voice), its capitals in the middle of a sentence (usually names), and the dots in abbreviations and numbers. It never changes your words.

**Set it up.** Import a punctuation model (Languages & Models → English → Punctuation Model). Importing the model switches on **Fix punctuation** in Voice Input settings. The model (`pcs_en`, about 67 MB) restores punctuation, capitals and sentence breaks. Like Moonshine, it is English only. Whisper adds its own punctuation, so this step is skipped while Whisper is your voice model. That's why **Fix punctuation** and the Punctuation Model row are greyed out then (tap either one to see why, with a button to choose your voice model).

**How it works.** Moonshine ends a sentence at every pause, so "We should. You know, leave early." is common raw output. The punctuation model reads the whole transcript at once and decides where sentences really end. When Smart corrections is on, a further check (the *sentence-break veto*) asks the language model whether each break the punctuation step added mid-utterance reads better joined. A clause starting with "who" or "which" is rejoined with a comma ("to Marcus. Who locked…" becomes "to Marcus, who locked…"), unless that sentence is a question.

### Smart corrections for voice

**What it does.** Reads each finished dictation and fixes what only the whole sentence can tell. Every kind of fix has its own switch, under Voice Input → Smart corrections. It never rephrases what you said.

**Set it up.** Import the Smart corrections model (about 470 MB, English only), then turn on **Use Smart corrections**. It needs an ARMv8.2 or newer processor, and the app tells you if yours isn't. The same model powers [Smart corrections for typing](#smart-corrections-for-typing).

**How it works.** Rules propose candidate edits, and a small language model (Qwen3-0.6B, run with llama.cpp) scores the sentence with and without each one. An edit is applied only when it makes the sentence clearly more likely, by a margin set per kind of fix. A guard then checks every change against what that kind of fix is allowed to do, so a sound-alike fix can never insert a new idea, for example. The model stays loaded for 2 minutes after a dictation, so the next one is quick. The exact rules for every kind of fix are in [Under the hood](#voice-smart-corrections-in-detail).

Here are the kinds of fixes it can make.

#### Misheard words

Swaps a word for one that sounds the same when the sentence calls for it, like "bulls" → "bowls" in a sentence about soup, "their" → "there", or "in to" → "into". Candidates come from a sound-alike lexicon built from a pronouncing dictionary (words with the same number of sounds and at most one different).

#### Names and your words

Spells names and words from your personal dictionary the way you do ("Katelyn" → "Caitlin" if Caitlin is in your dictionary), and fixes stray capital letters. Names the keyboard learned from your typing count too.

A spoken word, or a few words run together ("see ya mara" → "Xiomara"), must sound like the name. Several words must match the name's main pronunciation, and an exclamation is never part of a name, so "Oh darn" can't become a name that only loosely sounds like it.

*Tip.* Add unusual names you dictate often to your personal dictionary (long-press a suggestion → Add to dictionary, or use Copy learned words to dictionary on the Personal dictionary page).

#### Numbers, times and amounts

Writes spoken numbers as digits, like "twenty five dollars" → "$25", "three thirty pm" → "3:30 PM", "fifty percent" → "50%" and "the twenty first" → "the 21st".
- Amounts with a unit (dollars, percent and so on) always become digits, anywhere in a sentence. "Six hundred dollars profit" becomes "$600 profit". The same goes for digits followed by a spoken unit, so "800 dollars" becomes "$800".
- A plain number that starts a sentence stays in words ("Three hundred people came"), as style guides recommend.
- A lone number word with no unit needs more evidence, since "forty winks" and "the eleventh hour" read better spelled out.

#### Filler words

Removes "um", "uh", a "like" or "basically" between pauses, and words you said twice by accident ("I I think"). Fillers inside a quote are kept.
- The Filler words page (under Smart corrections) shows every built-in filler. Switch any off, add your own ("or whatever"), or turn off the whole built-in list.
- Fillers you add are only removed in the middle of a sentence.
- "You know" isn't a built-in filler, because it is often meant. Add it if you want it removed.

#### Self-corrections

When you change your mind mid-sentence, it keeps only the correction. "Let's meet Tuesday, no wait, Wednesday" becomes "Let's meet Wednesday". "I mean it" and "what I mean is" stay as they are.
- Built-in phrases include "I mean", "no wait", "sorry", "actually", "scratch that", "make that", "or rather", "let me rephrase" and more. You can switch any off or add your own on the Self-corrections page.
- **A cue at the very start of a dictation is left alone.** "No, I mean the blue folder." is usually correcting a message you already sent, so there's nothing earlier in it to replace.
- Corrections work best with a short pause before the phrase.

#### Quoted speech

Adds quotation marks when you repeat what someone said, so `I was like um what are you doing` becomes `I was like "Um, what are you doing?"`. It only does this when the sentence makes it clear (a question, or a reply that starts with "um", "oh" or "wow"). "He was like ten feet tall" stays as it is. The phrases that introduce a quote ("she said", "he goes", "I was like" and others) are listed on the Quoted speech page, where you can switch them off or add your own.

#### Emoji

Say "laughing emoji" → 😂, "heart emoji" → ❤️ or "thumbs up emoji" → 👍. Each emoji answers to its nicknames, Unicode name and keywords, in the plural, in any word order, and without "face" or "with". When you're talking *about* an emoji ("the heart emoji is my favorite"), the words stay. A name that fits several emoji gives none. On the Emoji page you can search to see which names give which emoji, switch names off, or add your own ("lol emoji" → 😂).

#### Voice commands

These let you format as you speak, with a short pause before or after the command. When the words are part of your sentence ("a new line formed at the door"), they stay as words. Here's what's built in.

| Say | Gives |
|---|---|
| new paragraph, paragraph break | a blank line |
| new line, next line, line break | a line break |
| period, full stop · comma · question mark · exclamation point/mark · colon · semicolon | . , ? ! : ; |
| ellipsis, dot dot dot · em dash · hyphen | … — - |
| open/close parenthesis · open/close bracket | ( ) [ ] |
| open quote(s) · close quote(s), end quote, unquote | " " |
| ampersand · at sign · hashtag · dollar sign · percent sign · asterisk · plus sign · equals sign · forward slash · underscore | & @ # $ % * + = / _ |
| smiley face · frowny face | :) :( |
| scratch that, delete that, delete last sentence | deletes the last sentence |
| delete last word | deletes the last word |

Edit, add or remove commands on the Voice commands page (one to a few words each).

#### Question marks

Turns a "." into a "?" when the sentence is clearly a question. This is part of Smart corrections itself and has no separate switch.

### Sensitivity dials

Smart corrections → **Sensitivity** has one dial per kind of fix (misheard words, names, question marks, numbers, fillers, quoted speech, emoji, voice commands and self-corrections). Each goes from *Fewest* to *Most* changes, with *Default* in the middle.

**How it works.** Each fix is applied only when the language model finds the sentence clearly better with it, by a set margin. Each step of a dial moves that margin by about four times the odds. Left asks for stronger evidence, and right accepts weaker evidence. Fixes the rules are certain of (like "new paragraph" said on its own after a pause) don't depend on the dials.

**Getting the most out of it.** Change one dial at a time and live with it for a few days. If one kind of fix changes words you meant, slide its dial left. If it misses fixes you want, slide it right.

### Advanced voice options

These are under Voice Input → **Advanced options**, and they apply when a Moonshine model is your English voice model.

- **Show words while speaking.** When it's off, each phrase appears when you pause. That uses less battery but takes a moment longer when you finish.
- **Live text refresh.** How often the words on screen catch up with your speech. Faster feels more live but uses more battery.
- **Stop after silence.** Finishes voice typing after you've been quiet for the time you choose, as if you tapped to finish. Background noise can keep it listening.
- **Processing mode** and **Processor cores.** How the model runs on your phone. Standard is usually fastest. Turn on **Show timing info** to compare settings on your phone.

### Choosing the voice input app

The microphone button uses Pluto's own offline voice typing by default. On the Voice Input page you can pick another voice typing app instead (such as Google voice typing, after you turn it on with **Open Android keyboard settings**). Just keep in mind that other apps may send your speech to online servers, and Pluto's privacy promises don't cover them. Pluto shows a reminder when you pick one.

### Managing models

- **Set up voice typing** (on the Voice Input page) walks you through the three model files. Each step has a **Download** button that opens the file in your browser and an **Import** button for picking the downloaded file, and a step shows ✓ Installed once it's done.
- **Languages & Models → English** lists the voice, punctuation and Smart corrections models you have. Pick which one to use, import more, or delete one you don't need (you'd have to import it again to use it).
- When you import a package, Pluto shows its name, type, language, license and where it came from before installing it.
- FUTO's Whisper models still work for other languages, and **Explore Whisper voice models** links to FUTO's page for them.
- Want to package a different model? [MODELS.md](MODELS.md) explains the format.

---

## Typing

### Smart corrections for typing

**What it does.** It rereads each sentence you finish and fixes typos that slipped past the usual autocorrect, using the words around them. It fixes two kinds, each with its own switch.

- **Fix typos that are real words.** These are slips that spell another word, which a spell checker can't catch, like "salt an pepper" → "salt and pepper" or "an email form my boss" → "from". It uses the [typing corrections list](#the-typing-corrections-list).
- **Attempt to fix missed typos.** These are misspelled words that got through anyway, for example because the typo was the middle suggestion and the space bar kept it. It works out the word you meant from the letters you typed *and* the sentence around them, like "ststy the game" → "start the game".

**Set it up.** It uses the Smart corrections model (the same one as voice). Turn it on in Text Prediction → Smart corrections for typing. English only. It needs the suggestion bar (that's where you see and undo its fixes), so it pauses while the bar is hidden and starts again when you show the bar. Hiding the bar while it's on asks you first.

**Using fixes.**
- Each fix shows up as a small button in the suggestion bar above the keyboard. Tap it to change the word back. The button follows your cursor: it shows the closest fix before the cursor (or the first one after it), so moving the cursor back through your text steps through the fixes. It gives way while you type a word, and comes back when you move the cursor.
- Fixes stay tracked while you edit around them, by the words on either side, so adding or deleting text before a fix doesn't lose it.
- The fixed word is also underlined in your text. If an app drops the underline later (Google Messages did once), Pluto puts it back on its own, without changing your text or moving the cursor. Some apps never draw it at all. Tap the eye on the button, or on a row of the list, to see exactly where the fix is. It selects the word for a few seconds (every app shows a selection and scrolls to it), then your cursor goes back. Typing during that time goes where you were typing, never over the word.
- With several fixes, "+N" opens the list of all of them. A fix whose own word you have since edited can't be undone from there.
- Right after an undo, the button offers **Stop** for a few seconds. Tapping it stops that fix everywhere by switching off its pair in your corrections list (or, for a missed typo, by adding the word to your never-change list).
- **Typing fix buttons on the right** puts the undo and list buttons near your right thumb. Turn it off to put them on the left.

**It learns from you.**
- *Undoing* a fix makes that same change more cautious, a little everywhere and much more in the same context (the words around it).
- *Fixing a miss yourself* makes it more ready. If a word it could have fixed was left and you change it into its pair word by hand ("pour" → "pout"), that fix needs less evidence next time, within limits.
- Only counts per change and per context are kept, never your text.

**Sensitivity.** There is one dial for typing. Slide left and it holds back, changing your words only when it's sure. Slide right and it steps in more readily.

**How it works.**
- After a sentence ends (or a few words after a possible slip, while you keep typing), the sentence is checked in the background. Each candidate fix is scored as the whole text, including up to 300 characters before the sentence, with the same language model as voice. A sentence gets at most two fixes, and a word that was fixed or that you changed by hand is never changed again.
- *Real-word typos.* Candidates are the pairs of the typed word, for example "an" → "and". A fix must raise the probability of the whole text by a margin, and more for a sentence's first word (which has less context). The words after the fix must not get less likely. Your own pairs need less evidence.
- *Missed typos.* A word none of the keyboard's dictionaries knows (the main dictionary, your personal dictionary, contacts and learned words) gets candidates from about 55,000 common words. They're ranked by how likely the slip is on a QWERTY keyboard. A neighbouring key costs less than a far one, and swapped, doubled and missing letters each have their own cost ("ststy" → "start" is three neighbour-key slips). Each candidate's score is how much more likely the sentence becomes, minus the cost of the slip. The best candidate is used only if it scores well **and** clearly beats the runner-up, and a near-tie is left alone rather than guessed. So it combines both kinds of evidence, the way you'd fill in the blank in "I've sent the order, so ____ the game now" knowing the typo was "ststy".
- Missed typos never touch words with capitals inside or in all caps ("MCP"), a capitalized word mid-sentence (likely a name), words with digits or symbols, words shorter than three letters, or anything a dictionary knows. Words under five letters need stronger evidence, since they have many neighbours and are often abbreviations ("brb").

**Getting the most out of it.**
- Add words you often mistype as your own pairs (see [Add correction](#add-correction)). Your own pairs are trusted more.
- If a fix is wrong, undo it rather than retyping the word, because undoing is what teaches it.
- It doesn't run in password, email, URL or no-suggestion fields.

### The typing corrections list

Text Prediction → Smart corrections for typing → **Typing corrections list** shows which typed words can be fixed into which. A word is only fixed into one of its pairs, and only when the sentence makes it clear.

- **Built-in pairs.** Real words one key off from other common words ("an"/"and", "or"/"of", "form"/"from"). Search a typed word to see its pairs and switch any off, or turn the whole built-in list off to use only yours.
- **Your pairs.** A typed word and one or more words you meant ("teh" → "the", "pour" → "pout").
- **Never change.** Words that are never fixed into another word.
- **Undo your changes** removes your pairs and never-change words and switches the built-in pairs back on.

#### Add correction

Select one word in any text field and an **Add correction** button appears in the suggestion bar. Tap it, type the word you meant and tap Save. The pair is added to your list, and the selected word is replaced. Next time you make that typo, Smart corrections can fix it.

### Adaptive typing heatmap

**What it does.** It learns from the times you hit a neighbouring key by mistake and slowly shifts each key's tap area toward where you actually tap, so you can type faster without aiming so carefully.

**Set it up.** It's on by default (Text Prediction → Adaptive typing heatmap). Open it to see your heatmap, switch it off, or reset what it learned. Portrait and landscape are learned separately.

**How it works.** The code is in `java/src/org/futo/inputmethod/latin/heatmap/`.
- For every letter key, `HeatmapModel` keeps running statistics of where taps landed relative to the key (average offset and spread, in units of the key's size). It doesn't store any text. Statistics are blended with a prior, so a key with few samples barely moves, and old samples fade so the model keeps following how you type.
- Learning uses words you finish. `RecentWordBuffer` holds recent words' raw tap positions for a moment. If you then correct a word (choose a suggestion, let autocorrect change it, go back and edit it, or delete and retype it), the taps are relabelled with the word you meant. `TapAlignment` accepts only plausible typos (same length, few substitutions, plain taps). Nothing is learned in password or incognito fields.
- The learned offsets shift and resize each key's effective hit area in `KeyDetector`, by at most half a key. The corrected tap positions are also passed to the suggestion engines.

**The heatmap screen.** The dots show where you recently tapped each letter, compared with the key, and the marker and line show how far that key has shifted to match you. It also shows how many taps it has learned in total and per key. Portrait and landscape (and split layouts) each get their own profile. **Reset what was learned** starts over (it also clears swipe learning), and the keys go back to normal until it learns again.

### Adaptive swipe learning

**What it does.** This does for swipe typing what the heatmap does for taps. It learns where your swipes really start, end and turn for each key, and nudges new swipes to match before they're read, so your swipes get recognized better over time.

**Set it up.** It's on by default (Swipe Typing → Adaptive swipe learning). **Strength** goes from Gentlest to Strongest and sets how quickly it trusts what it learned and how far it adjusts your swipes. You can see what it learned in the typing heatmap (the profiles marked "swipe"), and **Reset swipe learning** clears it without touching what it learned from your taps.

**How it works.**
- It learns from swipes you keep, and from swipes you fix (delete and swipe again, pick another suggestion, or go back and change the word).
- A swipe only counts when the path really agrees with the final word. Every letter has to be visited in order (within about 1.4 keys), the start and end have to match, there can't be extra sharp turns, and the length has to be about right. So a botched swipe is never learned. Fingers often lift off a little past the last key when swiping fast, so the last letter is learned from the path's closest approach instead.
- Swipe offsets are kept in their own profile, apart from your taps. New swipe paths are bent by at most 0.6 of a key before the recognizer reads them.
- Only per-key positions are saved, never words or swipe paths, and nothing is learned in password or incognito fields.

### The suggestion menu

Long-press a word in the suggestion bar to see what you can do with it.

- **Add to dictionary** puts it in your personal dictionary. If the word looks like a password or key, Pluto warns you first, since the personal dictionary can be read by other apps and keyboards.
- **Forget** stops the keyboard from suggesting it again.

### Always use middle suggestion

Like SwiftKey, space always picks the middle suggestion, even when the keyboard isn't sure. Words the keyboard doesn't know stay in the middle as you typed them. While it's on, the Autocorrect Threshold setting isn't used. It pairs well with [missed typo fixing](#smart-corrections-for-typing), which catches the typos this lets through.

### Correct words before punctuation

This is on by default, and typing punctuation like ! or ? corrects the word before it, just like space. When it's off, punctuation keeps the word exactly as you typed it, and symbols become part of the word. Space still corrects.

---

## Your words and names

Pluto uses the words you add and the names it learns in a few places (Smart corrections spells names your way, and typing fixes leave your own words alone). The Personal dictionary page (Text Prediction → Personal dictionary) has some tools for managing them.

- **Copy learned words to dictionary** lists the names and other new words the keyboard learned from your typing, so you can add them to Android's personal dictionary in one go. Words already there are skipped.
- Words that look like passwords or keys are listed separately under **Looks sensitive**, with a warning not to copy them.
- For any learned word you can **Forget from keyboard** (so it's no longer suggested) or **Never suggest** it for copying. Words you hide end up in **Hidden words**, where **Show again** brings them back.

Android's personal dictionary is shared with other apps and keyboards on the phone, so only copy what you're comfortable sharing.

## Privacy

- **No internet permission.** Models are imported from files, and nothing is uploaded.
- **Protect password-like words** (on by default, in Text Prediction). Words that look like passwords, keys or email addresses aren't learned. A "Looks like a password" notice lets you learn one anyway.
- **Copy learned words to dictionary** (on the Personal dictionary page) adds names and other new words the keyboard learned to Android's personal dictionary, so Smart corrections and other apps can use them. Words that look sensitive are flagged and not copied. Android's personal dictionary can be read by other apps, so only copy what you're comfortable sharing. You can hide words from this list, or make the keyboard forget them.
- What Smart corrections for typing learns is kept as counts per change and context, never your text.

## Themes

Pluto Classic (the default), Gilded Horizon, Ember Ridge, Horizon and Frost sit alongside FUTO's themes. **Follow system light/dark mode** switches between a day theme and a night theme of your choice when your phone changes mode.

## Starting settings

A few keyboard settings start differently from FUTO Keyboard, so a new install feels ready to use. You can change all of them, and importing a FUTO configuration keeps your own choices.

- **Number row** is on.
- **Key hints** (the small symbol on each key showing its long-press character) are on.
- **Long-press** opens after 220 milliseconds instead of 300.
- **Swiping on backspace** deletes whole words. Holding backspace still deletes letters.
- **Holding the space bar** moves the cursor (switch languages with the globe button in the actions bar instead).
- **Pluto Classic** is the theme.

## Backup and moving from FUTO Keyboard

**Export configuration** and **Import configuration** (under Backup and restore) save and restore your settings, learned words, personal dictionary and Pluto's own settings, like your typing pairs, phrase lists and emoji names. Coming from FUTO Keyboard? Export there and import in Pluto. Importing replaces Pluto's current settings, so do it first.

## Feature tips

The first time you meet a feature, a short tip appears over the top of the keyboard. For example, you'll see one

- for your first typing fix or missed-typo fix, with how to undo it,
- for your first undo, with what it teaches,
- the first time you fix a missed typo yourself,
- the first time you select a single word, with what Add correction does,
- the first time Smart corrections tidies a dictation,
- and the first time you swipe a word, with what adaptive swipe learning does.

Tips only appear for features you have switched on, each only once. Tap **Got it** to close a tip, or **Turn off tips** to stop them all. Under Settings → Help you can switch tips back on (**Show feature tips**) or see them all again (**Show tips again**).

## Help

Settings → Help & Feedback has the feature tip switches, a button to run the setup tutorial again, your version number, and links to report a problem or suggest a feature (on GitHub), to the project page, and to FUTO's guide for the features Pluto shares with FUTO Keyboard. Please report Pluto problems on GitHub rather than to FUTO.

## Getting the models

Pluto can't download anything by itself (that's the point of having no internet permission). Get model files from the [models page](https://github.com/ColinCPittman/Pluto-Keyboard/releases/tag/models) in your browser and import them without unzipping (Voice Input → Set up voice typing, or Languages & Models → English).

The first time you open Pluto's settings (with no models yet), a setup tutorial walks you through it. It takes you to Languages & Models and lights up what to tap next, first for the voice model, then punctuation, then Smart corrections. For each one it shows the model's row, the release page link, and the import button, then the setting the model switched on (for Smart corrections, the voice switch and then the typing switch, which stays off until you turn it on). It ends back on the Smart corrections page, pointing out the other switches there to set the way you like, with a **Finish tutorial** button. Every step has **Skip step** and **Skip tutorial**, and if you wander off to another screen a small bar lets you continue. Skipping a model's row or its import skips that model. On a first run, a model you already have skips straight to the settings it turns on. Running the tutorial again from Help shows every step as a reminder, even for models you have, and those steps have **Next** instead of Skip step. If you skip the voice model, voice typing uses Whisper, so the punctuation step only explains that punctuation is for Moonshine, and the Smart corrections step says it works best with Moonshine (it still helps with typing). Both have a **Set up Moonshine** button that takes you back to the voice model. Settings → Help can start the tutorial again (**Show the setup tutorial again**). To build your own model packages, see [MODELS.md](MODELS.md). To build the app and the official packages, see [BUILDING.md](BUILDING.md).

---

# Under the hood

This part is for anyone who wants the exact rules. It goes setting by setting and covers what decides each change, what the sliders really move, and the side effects worth knowing about. The numbers are the defaults in the current code.

- [How the scores work](#how-the-scores-work)
- [Voice Smart corrections in detail](#voice-smart-corrections-in-detail)
- [Typing Smart corrections in detail](#typing-smart-corrections-in-detail)
- [The typing heatmap in detail](#the-typing-heatmap-in-detail)
- [Adaptive swipe learning in detail](#adaptive-swipe-learning-in-detail)
- [Keyboard options in detail](#keyboard-options-in-detail)
- [Voice engine in detail](#voice-engine-in-detail)
- [Performance in detail](#performance-in-detail)
- [Feature tips in detail](#feature-tips-in-detail)

## How the scores work

Smart corrections (for voice and for typing) uses a small language model (Qwen3-0.6B, quantized to Q4_0 and run with llama.cpp). It never writes text itself. It only scores how likely a piece of text is, word by word, and Pluto compares the score of your text with the score of a changed version.

The difference is measured in log-probability points. You don't need the math, just this rule of thumb.

| Score difference | How much more likely the changed text is |
|---|---|
| 1 point | about 2.7 times |
| 1.5 points (one Sensitivity step) | about 4.5 times |
| 3 points | about 20 times |
| 6.5 points | about 665 times |

Every kind of fix has a margin, which is the number of points a change has to win by before it's made. A higher margin means more caution. A margin of 0 means "make the change if it's more likely at all", and a negative margin means "make it unless it's clearly worse" (used for fixes where the rules already did most of the deciding).

Some fixes are certain, which means the rules are sure enough that the model doesn't get a vote (it only picks between versions, such as where the punctuation goes). Certain fixes ignore the Sensitivity dials.

## Voice Smart corrections in detail

### The order things happen in

After Moonshine (or Whisper) finishes, punctuation runs first (only after Moonshine, since Whisper adds its own), then Smart corrections. Smart corrections works in this order.

1. **Sentence-break check.** Breaks the punctuation step added in the middle of one spoken phrase can be joined back up (see [sentence breaks](#sentence-breaks)).
2. **Rule-based fixes**, in this order (each only if its switch is on). Voice commands come first and run up to three times in a row (so two commands back to back both work), then emoji, quoted speech, filler words, self-corrections and numbers. Commands go first so a quote they open keeps the filler words inside it.
3. **Misheard words, names and capitals.**
4. **Question marks.**
5. **The guard**, a final check that every change is the kind that fix is allowed to make.

The speech model's own pause information (where it started a new phrase) goes along with the text, because voice commands and self-corrections only count at pauses.

**Text that's already in the field.** The last 120 characters before the cursor are shown to the model as context (so a name you just typed makes that name more likely), but nothing before your dictation is ever changed.

**Long dictations.** Up to 1,500 characters are checked in one go. Longer dictations are split at sentence ends. Only the first part gets the field's text as context, and the parts lose the pause information, so voice commands and self-corrections are less reliable in very long dictations.

### Margins and Sensitivity dials

These are the margins each kind of fix has to win by, and what one step of its Sensitivity dial does. Each dial goes from -2 (Fewest) to +2 (Most). A step right takes 1.5 points off the margins it controls (self-corrections use 0.4 per step, because they're scored per word, see below).

| Dial | Margins it moves (default) | At Fewest (-2) | At Most (+2) |
|---|---|---|---|
| Misheard words | word swap 2.0, exact homophone 3.1, near sound-alike 7.0, word boundary 3.0 | 5.0, 6.1, 10.0, 6.0 | -1.0, 0.1, 4.0, 0.0 |
| Names and your words | personal name 3.0, name respelling 0.0, capital letters 2.0 | 6.0, 3.0, 5.0 | 0.0, -3.0, -1.0 |
| Question marks | sentence end 0.0, tag ("right?") 0.0, follow-up fragment -3.0, command-like sentence 3.0 | 3.0, 3.0, 0.0, 6.0 | -3.0, -3.0, -6.0, 0.0 |
| Numbers | numbers 0.0, a lone number word 5.0 | 3.0, 8.0 | -3.0, 2.0 |
| Filler words | fillers 0.0, accidental repeats 3.0 | 3.0, 6.0 | -3.0, 0.0 |
| Self-corrections | per-word score -2.0, mirrored repair -3.5 | -1.2, -2.7 | -2.8, -4.3 |
| Voice commands | spoken punctuation 0.0, new line or paragraph -8.0, "scratch that" 0.0 | 3.0, -5.0, 3.0 | -3.0, -11.0, -3.0 |
| Quoted speech | quotes 0.0 | 3.0 | -3.0 |
| Emoji | (every emoji change is certain, so this dial has no effect today) | | |

The dials don't change certain fixes, the guard, how unusual a word has to be before it's even looked at, or the sentence-break check (which has no dial).

**Self-corrections are scored per word.** Taking words out of a sentence almost always makes its total score better (fewer words, fewer chances to be unlikely). So instead of the total, a self-correction is judged on the average score per word of the whole sentence. Its margin is negative because it works as a veto, and the rules (below) decide most cases.

**When fixes overlap**, the one with the bigger score wins, so a certain fix always beats a judged one.

### The guard

The guard compares the final text with what the speech model wrote, word by word, and undoes anything that isn't allowed.

- Allowed everywhere. Changing the type of punctuation between words (. , ? ! ; :), adding a final mark at the very end, and changing a first letter's case. A ? or ! that the speech model heard is never dropped.
- A word may only be swapped for one that sounds the same (checked with Double Metaphone codes, treating a silent leading h like a vowel, so "hour" matches "our"). The swap is refused if it changes a negation (n't, not, no, never), swaps two little function words (is, was, the, a, of, in, he, she and so on), only changes an ending (s, ed, ing, ly, er, est), changes the tense of the same verb (get and got), or touches a hyphenated word ("x-ray").
- Word joins and splits ("in to" and "into") only when the lexicon lists them.
- Names only when they're in your personal dictionary and really sound like what was heard.
- Each rule-based fix may only do its own job. Fillers may only delete filler words, self-corrections may only delete (1 to 4 words before a correction phrase), commands may only turn their own words into their symbol, numbers may only produce the number's written form, emoji may only replace "name emoji", and quotes may only add one pair of quotation marks.
- If more than 6 words (or 40 percent of the dictation, whichever is more) would have to be undone, the guard throws the whole result away and keeps what the speech model wrote.

### Misheard words

- **Which words get a look.** Only words the model finds surprising (a score below -4), and at most the 8 most surprising ones per dictation. Filler sounds, sentence openers like "so" or "well", acronyms, numbers, question words and words in your personal dictionary are never touched.
- **Candidates** come from a sound-alike list built from the CMU pronouncing dictionary (words with the same number of sounds and at most one different), up to 6 per word. A quick first check drops any candidate whose first piece is much less likely than the original's. Only exact homophones ("meat" and "meet") and listed near sound-alikes ("pool" and "poll") are used.
- **Winning.** A candidate must beat the original by 2.0 points in its sentence (plus up to 6 words after it). It then has a second test, where the gain minus how much more common the new word is in general (measured in neutral phrases like "The word is ...") must beat 3.1 points for an exact homophone or 7.0 for a near sound-alike. The first word of a sentence needs 6.5 more points on top, because there's less context to go on.
- **Word boundaries.** "in to" can become "into" and "every day" can become "everyday" (3.0 points). Splitting a word ("away" into "a way") also needs the word to be very surprising (a score below -9), because splits are rarely right. A sentence's first "A" never merges with the next word.
- **A name that swallowed a word.** "Marcus" can become "Mark is" or "Mark's", but only when Mark is a name you used earlier in the text or have in your dictionary.
- At most 6 of these extra spots (boundaries, names, capitals) are checked per dictation.

### Names and your words

- **Where names come from.** Your Android personal dictionary, plus capitalized words the keyboard learned from your typing (common words like "The" or "Hello" typed at a sentence start are dropped). Lowercase entries that aren't ordinary words count too ("kubectl", "pho").
- **What can become a name.** One to three spoken words that sound like the name. A single word needs to match the name's sound code. Several words run together ("see ya mara" for "Xiomara") must match the name's main pronunciation exactly, and at least one of the words must be unusual. A run stops at an exclamation, so "Oh darn" can't become a name. Ordinary words on their own ("Will", "Grace") never become names, and a lowercase word only becomes one of your lowercase words, never a capitalized name.
- **Respelling.** A capitalized word that isn't a common word ("Reese") is taken as the speech model's spelling of a name, and your dictionary's spelling ("Rhys") wins easily (margin 0.0). Turning other words into a name needs 3.0 points.
- **Capital letters.** A lowercase name or proper noun gets its capital ("jordan" to "Jordan", 2.0 points), but only the first letter changes ("wifi" stays "wifi"). A common word that the speech model capitalized only because it started a new phrase after a pause mid-sentence ("the | Toaster oven") is lowercased. Words in your dictionary are never lowercased.

### Question marks

- A "." at the end of a sentence becomes "?" when the model finds "?" more likely (margin 0.0).
- After tag words (right, okay, ok, yeah, correct, huh, no, yes, really) a comma can become "?" ("We're good, right? And...").
- A short follow-up right after a question that starts with And, Or or Plus ("Want pizza? And wings.") gets an easier margin (-3.0).
- A sentence that starts with a command word (let, tell, ask, get, send, call, text, remind, check, please and similar), an indirect question ("I wonder if...", "I'm not sure whether..."), or doesn't have a question's shape needs 3.0 points. Exclamations like "What a mess" or "How nice of you" and commands like "Have a great day" don't count as question-shaped.
- A comma left dangling at the very end becomes a period if the last sentence has at least 5 words and isn't a greeting or sign-off.

### Sentence breaks

The punctuation step sometimes ends a sentence in the middle of a phrase you said in one go. Pluto only looks at breaks it added inside one spoken phrase (breaks at your real pauses are never touched).

- It tries joining with a space, or with a comma before who, whom, whose and which ("to Marcus, who locked..."). A join needs 3.0 points.
- It skips a break before "who" or "which" when that sentence is a question ("We need a volunteer. Who wants to go first?").
- The next word only loses its capital if it's a plain common word (never "I", a name or an acronym).
- At most 6 breaks are checked per dictation, and when two joins are close together only the stronger one is made in the first pass.

### Filler words

- **Built-in list.** um, uh, er, erm, hmm, umm, uhm, uhh, hm, ah, eh, ummm, uhhh, hmmm, like, kind of, sort of, kinda, sorta, basically, literally, you see and so yeah. ("You know" isn't built in.)
- **Hesitation sounds** (um, uh, er, hmm and their spellings) are removed anywhere, and these removals are certain. "Ah" and "eh" are only removed inside a sentence, so "Ah, I see." stays.
- **Word fillers** (like, kind of, sort of, kinda, sorta, basically, literally, you see) are only removed inside a sentence with a pause on both sides ("it was, like, huge" yes, "I like it" no), never right before a number ("it was, like, twenty bucks" stays), and the model has to agree (margin 0.0).
- **"So yeah"** is only removed at the end of a sentence after a pause, never before a question mark.
- **Fillers you add** are removed only inside a sentence (not as the first or last word), with the model's agreement. If what you add matches a built-in kind ("you know"), it gets that kind's rule.
- Never removed inside quotation marks, never in ALL CAPS ("ER"), and not in "uh huh", "uh oh" or "um hmm". A dictation that's nothing but fillers is left alone.
- **Accidental repeats** ("I I think", "the the", "I think I think") delete the first copy, need 3.0 points, and stay within one sentence. They're left alone when said twice on purpose (a pause between the copies and after them, "Sorry, sorry, I didn't see you"), for words people often repeat on purpose (that, very, really, no, bye, knock, ha, bang, numbers and about 80 more), for names and doubled words ("Bora Bora", "mahi mahi"), and for "you, you know" or "you, you see".

### Self-corrections

- **The phrases.** There are 39 built in (I mean, no wait, sorry, actually, scratch that, make that, or rather, let me rephrase, my bad, hold on and more). Some need a pause before them (I mean, sorry, actually, rather and others), so "what I mean is" never counts. Multi-word ones like "no wait" and "sorry, I mean" don't need one. "Make it" only works between two numbers ("a table for four, make it five").
- **A phrase that opens the dictation is left alone.** If only lead-in words (no, oh, um, well, so, okay, sorry, wait, actually and similar) come before it, it's correcting an earlier message, not this one.
- **What gets replaced.** The correction is the words after the phrase, up to the next punctuation (at most 4). What it replaces is 1 to 4 words right before the phrase, in the same clause, and never longer than the correction. If the correction starts with the same word ("the red one, sorry, the blue one"), that sets the length. A phrase starting with on, at, the, my and so on is only replaced by one that also starts that way ("on Monday, I mean, Tuesday" replaces just "Monday"). A replacement that would take the sentence's first word is avoided when a shorter one works ("Text Sam, sorry, Alex" removes only "Sam").
- **When it's not a correction.** The word after the phrase can't start a new clause (I, you, it's, this, what, if, really, so, because and so on), so "I mean it" and "Actually, I think it's fine" stay. A one-word phrase with no pause after it is an ordinary word ("a long, rather boring meeting"). "Thanks, sorry, gotta run" stays because it would delete a whole sentence start.
- **Across sentences.** The speech model ends a sentence at every pause, so "We need three. Actually, four chairs." still counts (numbers), and so do multi-word phrases. But the correction then has to be 3 words or fewer ("...the movie. I mean, the ending was weird" is a new thought).
- **The vote.** Never certain. The sentence's per-word average must not drop by more than 2.0 (or 3.5 when the correction mirrors what it replaces, like two numbers or two names).

### Voice commands

- **The list** has 43 commands (new line, new paragraph, period, comma, question mark, quotes, brackets, symbols, smiley face, scratch that, delete last word and more). You can edit, add (1 to 4 words each) or switch off any of them.
- **Pauses.** A command needs a pause before or after it. A few unmistakable ones (multi-word quote and bracket commands, ampersand, asterisk, underscore, semicolon) don't.
- **When words stay words.** Not after a word that makes it a noun ("the period", "a comma", "a trial period", "my colon"), not before words like "of", "key" or "sign" ("period of time", "new line of products"), and "period" right after punctuation is emphasis ("You're not going, period.").
- **Certain or judged.** A command of 2 or more words said between two pauses ("Exclamation point." on its own) is certain. Everything else needs the model's agreement (margin 0.0). New line and new paragraph use a margin of -8.0, so the model can only stop them when they clearly don't fit.
- **Capitals.** After a sentence-ending mark or a new line, the next word gets a capital. After a comma, colon or semicolon, a word the speech model capitalized is lowercased if it's a common word.
- **Delete commands.** "Scratch that", "delete that" and "delete last sentence" have to start a sentence or phrase, and they remove the sentence or phrase before them (never the whole dictation). "Delete that" only works when what you say next shares a word with what it deletes, so "I sent you a photo. Delete that." stays as words. "Delete last word" removes just the one word before it and is certain.

### Quoted speech

- **Triggers.** There are 56 built-in phrases (said, asked, told me, goes, was like, texted, replied, whispered and more). A person has to come right before the trigger (I, he, she, they, we, you, a name, or a word like mom, boss, friend or doctor). Bare "like" only counts after I'm, he's, she's and similar.
- **What becomes a quote.** The rest of the sentence (up to 20 words), and only when it can only be speech. That means it's a question ("she asked what are you doing"), it opens with um, oh, wow, whoa, ugh or omg after a "was like" kind of trigger, it starts with a word like hey, no, yes, sorry or wait followed by a pause ("I said, no, I can't"), or the speech model heard it as a ? or ! sentence (but not "he yelled at me!" or "she said that?").
- **Certain or judged.** It's certain after a quotative trigger ("I was like um, what...") or when a pause follows the trigger and the quote is a question or starts with one of those words. Otherwise the model has to agree.
- The first quoted word gets a capital, a leading um gets a comma, and the quote ends with ? for a question or the speech model's own mark. Fillers inside the quote are kept.

### Emoji

- **Names.** Each emoji answers to a hand-picked nickname list (laughing and lol for 😂), its official Unicode name (with "face" and "with" dropped, so "smiling face with heart eyes" is also "smiling heart eyes"), its aliases and keywords, plurals, and two-word names in either order. About 9,000 names in all. Nicknames win over official names, which win over keywords.
- **Ambiguity.** If a name fits several emoji equally well, it gives none. Your own names override built-in ones.
- **When it stays as words.** After a word like the, a, my, this or favorite ("the heart emoji is my favorite"), before a word like is, for, keyboard or means ("emoji keyboard"), and "love", "hug" or "kiss" used as a verb ("I love emoji").
- Every emoji change is certain. "Emoji" can also be heard as emojis, emojie or "a moji".

### Numbers, times and amounts

- **Always converted (certain).** Amounts with a unit ("six hundred dollars" to "$600", "fifty percent" to "50%", "two dollars and fifty cents" to "$2.50"), and digits the speech model already wrote followed by a unit word ("800 dollars" to "$800", "30 percent" to "30%").
- **Converted when the model agrees.** Times with am or pm ("three thirty pm" to "3:30 PM"), times without am or pm only after words like at, by, until or around ("meet at three thirty"), years ("nineteen ninety five" to "1995"), numbers of 10 or more ("two hundred and fifty" to "250"), and ordinals of 10 or more ("twenty first" to "21st").
- **A lone number word** with no unit ("forty", "eleventh") needs 5.0 points, because "forty winks" and "the eleventh hour" read better spelled out.
- **Never converted.** Single digits (zero to nine), ordinals below 10, "5 dollar bill", "$800 dollars", "ten four", "twenty four seven", "the nineteen eighties", and capitalized names like "Ocean's Eleven". A plain number at the start of a sentence usually stays in words, because the model prefers it that way there.

### Your phrase lists

Filler words, self-correction phrases, quote triggers, emoji names and voice commands can all be edited. Pluto only saves your changes (what you added and what you switched off), so the built-in lists can grow in an update without touching your edits. Each list (except commands) also has a switch for its built-in entries, and a phrase you type that matches a built-in one gets the built-in phrase's special rules.

### Speed, memory and when models load

(How all this is made fast is in [Performance in detail](#performance-in-detail).)

- **When you open voice typing**, the voice, punctuation and Smart corrections models start loading in the background while you talk.
- **After you finish**, Smart corrections usually takes about 0.3 to 0.5 seconds for a short message on a mid-range phone, and a second or more for long ones (each kind of fix does its own pass over the text).
- **Staying loaded.** The Smart corrections model (a package of about 470 MB) stays in memory for 2 minutes after the voice window closes, so the next dictation doesn't have to reload it (that takes about a second). Moonshine stays for 2 minutes and the punctuation model for 3.
- **Emoji names** are built in the background a few seconds after the keyboard starts (it takes a couple of seconds), so your first dictation doesn't wait for them.
- **Language and phone.** English only, and it needs an ARMv8.2 or newer processor. Smart corrections runs after Whisper too, it just has less to fix there.
- **Defaults.** Misheard words, Names and your words, Quoted speech and Emoji start on. Numbers, Voice commands, Filler words and Self-corrections start off.

## Typing Smart corrections in detail

### When a sentence is checked

- **Fields.** It's off in password, incognito, email, web address and no-suggestion fields, and in fields where autocorrect isn't allowed. It's English only.
- **Timing.** A check runs after a space or punctuation, never in the middle of a word. A finished sentence (ending in . ? or !) is checked once.
- **Early checks.** While you're still typing a sentence, a word that has a pair gets an early check 3, 4, 5 and 6 words later, and a missed typo (a word no dictionary knows) 2, 3, 4 and 5 words later. Checks also run while you're in the middle of a word (they look at the text before it), and a fix they find goes in when that word ends, so nothing changes under your fingers. A check for an unfinished sentence you've already typed past is skipped in favor of the newer text.
- **Length.** Sentences shorter than 3 words aren't checked.
- **Number of fixes.** Each check makes at most one fix (the one that wins by the most), and a sentence gets at most 2.
- **Words left alone.** A word you picked from the suggestions, backspaced into, already got fixed, or undid is never changed again.
- **What the model sees.** Up to 300 characters before the sentence, plus the sentence itself (never anything after the cursor).
- **Busy keyboard.** Checks run in the background. A fix found while you're typing a word waits until the word ends, and it's only applied if the text it checked hasn't changed.

### Typos that are real words

- **Candidates.** A typed word can only become one of its pairs (the built-in list has about 7,800 typed words, like "an" and "and", plus your own pairs).
- **Winning.** The whole text has to become 6.5 points more likely (about 665 times). The words after the fix can't get worse by more than 1.5 points.
- **First word.** The first word of a sentence needs 3 more points (9.5), since there's less context.
- **"then" and "than".** "Then" to "than" after a comparative word (more, less, better, or any word of 5 or more letters ending in "er") needs 3.5 fewer points.
- **Your own pairs.** They need 4 fewer points, but never less than 1.5. That 1.5 floor applies to every real-word fix, whatever it has learned.

### Missed typos

- **Which words.** Only in finished sentences. The word must be letters only, at least 3 letters long, lowercase (or capitalized only at a sentence start), unknown to the keyboard's dictionaries (including your personal dictionary and learned words), unknown to the model's word list, and not on your never-change list.
- **Too many unknown words.** If a sentence has more than 2 such words, none of them is fixed (it's probably another language, code or jargon).
- **Candidates.** Up to 10 of about 55,000 common words, cheapest slip first. The slip can't cost more than 4. The candidate's length can differ by at most 2 letters, and its first letter must match, be next to the typed first letter, or match the typed second letter (for a stray extra first letter).
- **What a slip costs.**

  | Slip | Cost |
  |---|---|
  | A neighbouring key | 1 |
  | A key further away | 2.5 |
  | An extra letter (doubled or next to a neighbour) | 1 |
  | An extra letter (anywhere else) | 2 |
  | A missing letter | 1.5 |
  | Two letters swapped | 1 |

- **The score.** How much more likely the sentence becomes, minus 2 points per unit of slip cost (so one neighbour slip costs 2 points).
- **Winning.** The best candidate must score at least 2 points, and beat the runner-up by at least 3 points (if there's no runner-up, that test passes). A near tie is left alone rather than guessed.
- **Short words.** They need 2 more points for each letter under 5 (4 for a 3-letter word, so 6 in total), because they have many neighbours and are often abbreviations.

### The Sensitivity slider (typing)

| Step | Real-word fix needs | Missed typo needs | Missed typo must beat the runner-up by |
|---|---|---|---|
| -2 | 9.5 | 5.0 | 4.5 |
| -1 | 8.0 | 3.5 | 3.75 |
| 0 (default) | 6.5 | 2.0 | 3.0 |
| +1 | 5.0 | 0.5 | 2.25 |
| +2 | 3.5 | -1.0 | 1.5 |

The first-word extra, the "then/than" relief, your own pairs' relief and the 1.5 floor don't change.

### How it learns from you

Pluto keeps counts per change ("an" to "and") and per change in a context (the words just before and after, stored only as a hash). It never stores your text.

- **Undoing a fix** adds 1.5 points of caution for that change everywhere, and 4 points in that context.
- **Fixing a miss yourself** (a word with a pair that was left alone, which you then change into its pair word) takes 1 point off everywhere and 2 points in that context. This only works for real-word pairs.
- **Limits.** The two cancel each other out, one for one. In total a change can get at most 15 points more careful or 4 points more ready.
- **Missed typos.** Caution from undos also applies to missed typos, and it widens the lead over the runner-up they need.

### Undo, Stop and Add correction

- **Undo.** Tapping the fix button in the suggestion bar puts your word back (only if the 20 characters around it haven't changed), teaches caution, and offers **Stop** for 8 seconds or until you type again.
- **Stop.** It switches off that pair in your corrections list (removing it from your own pair, or switching off the built-in pair). For a missed typo it adds the word to your never-change list.
- **Add correction.** It appears when exactly one word is selected (up to 40 characters, with spaces or punctuation around it allowed). Save adds your pair and replaces the selected word if it's still selected.
- **Changes apply right away.** Pair and setting changes count from the very next check.

### Memory and speed (typing)

- **Sharing.** Typing and voice share the same model.
- **Loading and release.** It loads on the first check in a text field and is freed 1 minute after the last check, or 5 seconds after you leave the field (unless voice typing is open).
- **Speed.** Checks run in the background, so typing never waits. A check usually takes a fraction of a second, longer when it loads the model or the sentence has many candidates.

## The typing heatmap in detail

- **Trust builds gradually.** A key with n learned taps is trusted n / (n + 20), so 20 taps give half trust and 60 give three quarters. There's no hard minimum.
- **It keeps following you.** After about 300 taps on a key, older taps fade out (half of their influence is gone after about 200 more taps), so the heatmap follows how you type now.
- **Limits.** A key's tap area can move at most half a key, and grow or shrink by up to 25 or 20 percent (compared with how spread out your taps are in general). A touch more than half a key outside a key's normal area can never land on it. A touch well inside a key (0.3 key deep) always counts as that key, whatever its neighbours learned.
- **Which keys.** Only letter keys on the main keyboard. Space, backspace, shift and symbols keep their normal areas, and capitals train the same key as lowercase.
- **What it learns from.**
  - Every finished word typed with plain taps teaches its keys.
  - Corrections show which key you meant. Picking a suggestion, autocorrect, backspacing within a word and retyping it, going back to change a word, or deleting words and retyping them all count.
  - Words are held for about two sentences (24 words) before they're learned, so a fix you make soon after still counts.
  - Everything waiting is learned when the keyboard closes.
- **What it won't learn from.**
  - Corrections that change the length of the word, change more than a third of its letters, or swap two letters ("teh" to "the").
  - Words with digits, apostrophes, hyphens or typed symbols.
  - Swipes, and taps that weren't real screen touches.
  - Corrected taps more than 0.9 key from the key you meant.
- **Never learned in** password or incognito fields, fields without suggestions, or with Personalized dictionaries off.
- **Turned off with TalkBack** (explore by touch), so keys stay where they're announced.
- **Profiles.** Each keyboard layout gets its own profile for portrait and landscape (and split). The suggestion engine also gets your taps adjusted to where you meant them.
- **Saving and reset.** It's saved every 25 learned words and when the keyboard closes, and it's included in Export configuration. **Reset what was learned** wipes both tap and swipe learning.

## Adaptive swipe learning in detail

- **What it learns from.** Swipes you keep, plus swipes you fix (one backspace then swipe again, pick another suggestion, or go back and change the word). Two-finger swipes don't count.
- **A swipe only teaches when it clearly matches the final word.**
  - The start is within 1.4 keys of the first letter.
  - Every letter has a path point within 1.4 keys.
  - The closest approach to the last letter (within the last 1.5 keys of the path, since fingers lift off past the last key) is within 1.4 keys.
  - Every sharp turn (more than 70 degrees) is within 1.4 keys of one of the word's keys.
  - The path isn't far shorter or longer than the straight route between the letters.
  - Anything else is skipped.
- **What it measures.** Only where you start, where you come closest to the last letter, and the letters where your path actually turns. Letters you pass on a straight run say little about your aim, so they're not used.
- **How it adjusts.** Before a new swipe is read, each point of the path is nudged toward where you mean, blended from the nearby keys' learned shifts. A key's shift never moves the path more than 0.6 of a key.
- **Strength.** It changes how quickly it trusts what it learned and how much of it is applied (not what it learns).

  | Strength | Swipes for half trust | Share of the learned shift applied |
  |---|---|---|
  | Gentlest | 60 | 50 percent |
  | Gentle | 40 | 75 percent |
  | Default | 25 | 100 percent |
  | Strong | 15 | 120 percent (capped at 0.6 key) |
  | Strongest | 10 | 140 percent (capped at 0.6 key) |

- **Independent of taps.** It works even with the tap heatmap switched off, but has the same privacy limits (no password or incognito fields).
- **Separate profiles.** Swipe data lives in its own profiles next to the tap ones.
- **Reset.** **Reset swipe learning** clears only swipe learning.
- **What's stored.** Only per-key positions, never words or swipe paths.

## Keyboard options in detail

### Always use middle suggestion

- **What it does.** Space (and punctuation, if "Correct words before punctuation" is on) always takes the middle suggestion, without checking how sure the keyboard is.
- **When it's available.** It's off by default, and only works while autocorrect is on.
- **Safety rules.** The middle word only replaces what you typed when the change is typo-sized (up to 2 changed letters for words of 6 letters or fewer, 3 for longer words). A single letter is never replaced, and a valid word you typed isn't swapped for a longer version of itself. Anything else (an unknown word, "blahflarg") stays in the middle as you typed it.
- **Never autocorrected, even here.** Words with digits or dashes, mostly-capitals words, web addresses and email addresses, and words with a symbol typed into them.
- **Strip layout.** Your typed word moves to the left slot so you can still tap it.
- **Autocorrect Threshold** is greyed out while this is on.

### Correct words before punctuation

- **On (the default).** Punctuation corrects the word before it just like space, except - @ _ and the backtick.
- **Off.**
  - A symbol typed at the end of a word becomes part of the word ("blahflarg!") and nothing is corrected until space.
  - A word with a symbol in it is kept exactly as typed, even at space.
  - When such a word is learned, trailing punctuation is stripped first.

### Protect password-like words

On by default. Words that look like these are never learned automatically (whether by autocorrect, space or anything else).

- **Shapes.** Email addresses, runs of 8 or more digits (or 10 in total), hex strings of 16 or more characters, and known key formats (sk-, ghp_, AIza, AKIA and others).
- **Letters and digits mixed.** A chunk of 5 or more characters that mixes letters and digits (Tr0ub4dor, hunter2, password123) counts. Exceptions are ordinals (21st), decades (1990s) and number-plus-unit (10am, 64GB, 1080p). Short mixes (MP3, PS5) are fine unless there are two or more in one word.
- **Symbols.** Symbols outside normal shapes count (P@ssw0rd, Pa$$word, user:pass). Normal shapes are things like U.S., 3.14, google.com, 24/7, AT&T, 10:30 and 1,000.
- **Random-looking strings.** No vowels in 10 or more letters, wildly mixed case, or base64-like strings.

Anything under 4 characters is never flagged. When you deliberately pick a flagged word from the suggestions, Pluto shows "Looks like a password" with **Learn anyway** (for up to 8 seconds). The same rules power the warning in **Add to dictionary** and the **Looks sensitive** section of Copy learned words.

### Forget, Never suggest and Hidden words

- **Forget (long-press a suggestion).** Adds the word to your Word Blacklist and removes it from what the keyboard learned, so it's never suggested again (undo it on the Word Blacklist page).
- **Forget from keyboard (Copy learned words).** Removes it from what the keyboard learned, but doesn't blacklist it, so it can be learned again.
- **Never suggest (Copy learned words).** Only hides the word from the copy list (it still works as a typing suggestion). Hidden words are listed under **Hidden words**.
- **The copy list** only shows learned words that aren't already in the main dictionary or your personal dictionary. Copied words get the keyboard's language and a medium frequency.

### Themes

When **Follow system light/dark mode** is on, the day theme defaults to Pluto Frost and the night theme to Pluto Classic. If a chosen theme isn't available, those defaults are used.

## Voice engine in detail

- **Which engine.** Moonshine is used only when it's your chosen voice model, the language is English, and its files check out. Otherwise Pluto uses Whisper. Importing a Moonshine package selects it for you.
- **Splitting.** Moonshine splits speech into phrases at pauses, using Silero VAD if the package has it (otherwise a simpler loudness check). A phrase is capped at 15 seconds. After 10 seconds it's cut at the next quiet moment between words, and the overlap is merged so no words are lost or doubled.
- **Live text refresh.** Fast every 0.25 seconds, Normal every 0.5 (default), or Slow every second.
- **Stop after silence.** Off, or 1, 1.5, 2 (default), 3 or 5 seconds. It only starts counting after you've started talking. With Whisper it's fixed at 2 seconds.
- **Processing mode.** Standard CPU (default), XNNPACK, or the phone's AI chip (NNAPI, experimental). If a mode fails to load or run on your phone, Pluto falls back to Standard by itself.
- **Processor cores.** Auto uses your phone's fast cores (2 to 4). More threads on slow cores tends to make things slower, not faster.
- **Punctuation model.** It restores periods, commas, question marks, capital letters and sentence breaks. It keeps a ? or ! the speech model heard, never changes, adds or drops words, and only runs after Moonshine. (The app can also read punctuation packages in the `horizon` format, see [MODELS.md](MODELS.md), but none is offered on the release page.)
- **Cancel.** It stops recording (or the processing after it), erases what that dictation typed, and closes the voice window. Once the text is in, Cancel no longer does anything.
- **Other voice apps.** While one is selected, Pluto's own voice settings are hidden. Pluto shows a privacy reminder for any app outside a short list of offline ones (FUTO Voice Input, Transcribro and Transcribe).

## Performance in detail

Pluto runs three models on the phone itself (the voice model, the punctuation model and the Smart corrections model), so a lot of the work went into making them quick without using more memory than they need. This is how.

### What happens when you open voice typing

The moment the voice window opens, all three models start loading in the background, while you're still talking. You only notice loading when a dictation is very short and a model hasn't finished yet.

- **Moonshine** (the voice model) loads on its own thread and is ready before your first words need it in almost every case.
- **The punctuation model** loads at the same time (about 0.8 seconds on a mid-range phone).
- **The Smart corrections model** loads too (about 0.9 seconds). If it's already loaded from a recent dictation or from typing, nothing happens.
- **The emoji names** (about 9,000 of them) are built a few seconds after the keyboard itself starts, long before you open voice typing, because building them takes a couple of seconds and the first dictation used to wait for it.

### Keeping models loaded

Loading a model costs time, and keeping it costs memory, so each one has a keep-alive time that balances the two.

| Model | Size | Kept loaded after use |
|---|---|---|
| Moonshine (voice) | 39 to 239 MB depending on size | 2 minutes |
| Punctuation | about 67 MB (English) | 3 minutes |
| Smart corrections | about 470 MB | 2 minutes after the voice window closes, or 1 minute after the last typing check |

A model is never unloaded while it's in use, and while the voice window is open the Smart corrections model stays put even if typing's own timers run out. (Before this was fixed, a 5-second typing timer could unload the model in the middle of a dictation, and it then had to reload as soon as you finished.)

These two changes made a big difference. On the test phone (a MediaTek Dimensity 7050), the wait after finishing a dictation went like this.

| | Before | After |
|---|---|---|
| Median wait after you finish | 2.3 seconds | 0.46 seconds |
| Dictations that had to reload the model first | 13 of 25 | 0 of 9 |
| Typical short message | about 1.3 seconds | 0.3 to 0.45 seconds |

### How Smart corrections avoids redoing work

The language model scores text one token (a word or a piece of a word) at a time, and that's the expensive part. Pluto keeps the number of tokens it has to score as small as possible.

- **It remembers the text it already read.** The model keeps its working memory (its "KV cache") for the current text. When it scores a changed version, it reuses everything up to the first changed word and only reads from there on. A fix near the end of a long dictation costs almost nothing extra.
- **Only the nearby text is re-read.** A candidate fix is scored over the rest of its sentence plus 6 more words, not the whole dictation.
- **Candidates are scored together.** All the candidates for one kind of fix go through the model at once as parallel versions (up to 8 at a time), in batches of 128 tokens. Bigger batches were tried and were about twice as slow on the phone.
- **Cheap filters come first.**
  - Only words the model finds surprising are looked at, and at most 8 per dictation.
  - Each word gets at most 6 candidates.
  - A quick check of each candidate's first piece drops the unlikely ones before they're fully scored.
  - At most 6 extra spots (word joins, names, capitals) and 6 sentence breaks are checked.
- **No text is generated.** The model never writes anything. It only scores, which is much faster than generating text.

### Using the phone's fast cores

Most phones have a few fast cores and several slow ones, and a calculation split across a fast and a slow core runs at the slow core's pace.

- **Smart corrections** uses 2 threads and pins them to the phone's 2 fastest cores (found from the cores' capacity ratings, or their top speed if those aren't available).
- **Its weights are repacked** when it loads, into the layout the fastest processor routines need. That makes every check faster, at the cost of keeping that copy in memory.
- **Moonshine** uses Auto threads, which means your phone's fast cores (2 to 4). You can change this in Advanced options, but more threads on slow cores usually makes it slower.
- **The punctuation model** picks its own thread count.

### Live voice typing

- **Streaming.** Moonshine is a streaming model, so it works on your speech as you talk instead of waiting until you stop. The words on screen catch up every 0.5 seconds by default (Live text refresh).
- **Short phrases.** Speech is split into phrases at your pauses (capped at 15 seconds each), so the model only ever works on a short stretch of audio. That's why dictation can go on as long as you like without slowing down.
- **Catching up.** If the phone falls behind, audio queues up and the next refresh handles a bigger piece, so nothing is lost.
- **Processing mode.** Standard CPU is usually fastest. XNNPACK and the phone's AI chip (NNAPI) are there to try, and if one fails to load or run, Pluto quietly falls back to Standard. "Show timing info" tells you how long you spoke and how long processing took, which is the easiest way to compare settings on your phone.

### Punctuation

The punctuation step takes about 20 to 90 milliseconds per dictation, because it's a small model that reads the whole transcript in one pass. Long transcripts are read in overlapping windows (256 pieces with 16 overlapping), so a sentence on a window edge isn't cut off.

### Typing checks

- **Off the main thread.** Typing checks never block your typing. They run in the background after a space or punctuation, and a fix found mid-word waits until the word ends.
- **Small window.** Only up to 300 characters before the sentence are scored.
- **Few candidates.** Real-word typos only consider their listed pairs, and the same quick first-piece filter drops unlikely ones.
- **Missed typos.** These are capped at 10 candidates per word, and a sentence with more than 2 unknown words is skipped entirely, which also keeps the check quick.
- **No repeats.** A finished sentence is checked once, and a check that's already running is never thrown away. It just runs again afterwards if the text changed.

### Safety valves

- **One at a time.** Only one Smart corrections check runs at a time (voice and typing share the model).
- **Cancel stops it.** Cancelling a dictation also stops a check that's in progress, and the text is then left as it was.
- **Memory sized to fit.** The model's working memory is sized to the text (at least 512 tokens, growing only when needed), and dictations over 1,500 characters are checked in parts.
- **Failures are harmless.** If a step fails for any reason, it's skipped and its input is passed on, so you never lose a dictation.

## Feature tips in detail

- **One at a time.** A tip waits on screen until you tap Got it or Turn off tips. Only one shows at a time, and a tip that comes up while another is showing is skipped until next time.
- **When a tip counts as seen.** Only when you dismiss it. Turn off tips also switches tips off, and Show tips again (in Help) clears the seen list and switches tips back on.
- **Swiping.** Its tip comes after your first swiped word, while Adaptive swipe learning is on.
- **Add correction.** Its tip only appears after a single word has stayed selected for 1.5 seconds. It disappears again, still unseen, if the selection grows past one word.

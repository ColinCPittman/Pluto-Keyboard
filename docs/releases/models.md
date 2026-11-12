# Pluto Keyboard models

This page always has the model files for Pluto Keyboard, whatever the latest app version is. You only need them for voice typing and Smart corrections. The keyboard itself is on the [latest release](https://github.com/ColinCPittman/Pluto-Keyboard/releases/latest).

Pluto has no internet permission, so it can't download these by itself. Download the files you want to your phone, then import them in Pluto (don't unzip them). The app's Download buttons open this page.

## Which files to download

**1. A voice model (pick one).** This is what turns your speech into text. English only, and other languages keep using FUTO's Whisper models.

| File | Size | Pick it when |
|---|---|---|
| `pluto-voice-nemotron-en.zip` | about 475 MB | **Recommended for a recent, fast phone.** NVIDIA Nemotron shows your words as you talk, and with the punctuation model it was the most accurate voice model in Pluto's tests. |
| `pluto-voice-moonshine-small-en.zip` | about 124 MB | **Recommended for most other phones.** Shows your words as you talk, a good balance of accuracy and speed. |
| `pluto-voice-moonshine-medium-en.zip` | about 239 MB | You have a fast phone and prefer Moonshine. Close to Nemotron in Pluto's tests. |
| `pluto-voice-parakeet-110m-en.zip` | about 135 MB | You'd rather have your words typed in one go when you finish speaking. NVIDIA Parakeet writes numbers as words, and Smart corrections' Numbers (on by default) turns them into digits. |
| `pluto-voice-moonshine-tiny-en.zip` | about 39 MB | Your phone is older or slow, or Small feels laggy. Fastest, but misses more words. |

**2. Punctuation (optional, recommended).** Moonshine puts a period at almost every pause, and Nemotron and Parakeet often leave the period off the end and miss question marks. After you stop speaking, this replaces their periods and commas with real sentences, commas and capital letters. English only.

| File | Size | Pick it when |
|---|---|---|
| `pluto-punctuation-en.zip` | about 67 MB | **Recommended** with any of the voice models above. |

**3. Smart corrections (optional).** Fixes words that sound alike but don't fit the sentence, missed question marks and capitals for names, and adds the extras (numbers as digits, voice commands, filler words, self-corrections, quoted speech and emoji by name). Smart corrections for typing uses it too.

| File | Size | Pick it when |
|---|---|---|
| `pluto-smart-corrections-en.zip` | about 470 MB | You dictate in English and want cleaner text, or want Smart corrections for typing. Needs an ARMv8.2 or newer processor, and the app tells you if yours isn't. |

Not sure? Get **Nemotron** if your phone is recent and fast, otherwise **Moonshine Small**, plus **English punctuation**. You can always add Smart corrections later.

`SHA256SUMS.txt` lists a checksum for every file, if you want to verify a download.

## Set up voice typing

1. Download the model files you want to your phone (don't unzip them).
2. In Pluto Keyboard's settings, open **Voice Input → Set up voice typing**, or **Languages & Models**, then your language.
3. Import each file you downloaded.
4. Select your voice model in the Voice Input Model list. Importing a punctuation or Smart corrections model switches it on for you.

The first time you open Pluto's settings, a short tutorial walks you through these steps for the voice model you picked, and at the end it can switch on the Smart corrections extras that helped that model most in testing (you can skip it, and Help can run it again).

## Updating

These files don't change with every app update. When one does, it's replaced here and the app's release notes say so. Until then, the files you already imported keep working.

To build your own model packages, see [docs/MODELS.md](https://github.com/ColinCPittman/Pluto-Keyboard/blob/main/docs/MODELS.md).

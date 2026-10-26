# Building Pluto Keyboard

This page is for anyone who wants to build the app or the model packages themselves. If you just want to use Pluto, the [README](../README.md) is the better place to start.

## What you need

- **JDK 17 to 24.** The Gradle wrapper downloads Gradle 8.14.3 itself.
- **Android SDK** with platform `android-35` and build-tools 35 (Android Studio installs these for you). Gradle finds it through `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=...`.
- **Android NDK `28.2.13676358`** and **CMake `3.22.1`** (both installable from the SDK Manager). They're needed for the keyboard's native code and for the llama.cpp-based Smart corrections library.
- **Python 3**. Gradle runs a small script during the build to generate keyboard text resources (`python` on Windows, `python3` elsewhere).
- **Git**, with submodule support.

## Get the source

The repository uses submodules (keyboard layouts, themes, translations, native libraries and llama.cpp), so clone recursively.

```
git clone --recursive https://github.com/ColinCPittman/Pluto-Keyboard.git
cd Pluto-Keyboard
```

If you forgot `--recursive`, run this.

```
git submodule update --init --recursive
```

The Smart corrections library needs `voiceinput-shared/src/main/cpp/llama.cpp` to be populated. If that folder is empty, the submodules didn't get fetched.

## Build the app

```
./gradlew assembleStableRelease
```

(On Windows use `gradlew.bat`, or run `./gradlew` from Git Bash.) The APK ends up under `build/outputs/apk/stable/release/`.

A few variations come in handy.

| Command | Result |
|---|---|
| `./gradlew assembleStableRelease` | Release APK for **arm64-v8a** only. This is what Pluto ships. |
| `./gradlew assembleStableRelease -PallAbis` | Includes every ABI (for example x86_64 for an emulator). The ONNX Runtime library alone adds roughly 55 MB per extra ABI. |
| `./gradlew assembleUnstableDebug` | Debug build with an `.unstable` application ID suffix, so it installs next to the stable build. |

The version name and code come from the `VERSION_NAME` and `VERSION_CODE` environment variables if they are set, and otherwise fall back to `git describe --tags` and `git rev-list --count HEAD`.

### Signing a release

Without any setup, Gradle signs the release build with the shared debug keystore (`java/shared.keystore`). That's fine for testing, but it's not a key you should publish updates with. To sign with your own key, create a `keystore.properties` file in the repository root (it is git-ignored) like this.

```
storeFile=/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` is resolved relative to the repository root. Keep this file and the keystore out of version control. Android only installs an update if it is signed with the same key as the installed app, so don't lose your key (back it up somewhere safe).

## Building the model packages

Since the app has no internet permission, voice models come in as `.zip` "model packages", and the scripts in `tools/` build them. Each package is a flat zip with a `futo-model.json` manifest first (`format`, `kind`, `id`, `name`, `language`, `engine`, `license`, `source`), followed by the model files. The `name` is what the app shows in its model lists.

The scripts only need the Python standard library, unless noted otherwise.

If you want to make packages from other models (what the manifest fields mean and which model types Pluto can run), have a look at [MODELS.md](MODELS.md).

### Voice models (Moonshine, English)

```
python tools/package-models.py --out ./packages
```

This downloads the Moonshine streaming weights and the Silero VAD model, caches them, and writes three packages.

- `pluto-voice-moonshine-small-en.zip` (recommended)
- `pluto-voice-moonshine-medium-en.zip`
- `pluto-voice-moonshine-tiny-en.zip`

Use `--models tiny,small,medium` to pick some, and `--cache DIR` for the download cache.

### Punctuation models

```
python tools/package-punctuation-models.py --reduce --out ./packages
```

This writes `pluto-punctuation-en.zip` (pcs_en, engine `pcs`), the one on the release page, and `pluto-punctuation-multilingual.zip` (Horizon-Labs small, engine `horizon`), which isn't released because punctuation only runs after Moonshine, and Moonshine is English only. `--reduce` shrinks the ONNX files by storing weights as fp16. It needs `pip install onnx numpy`, and `--python` lets you pick the interpreter that has them. Use `--only pcs` or `--only horizon` to build one. `--work DIR` is the download cache.

### Smart corrections (context model)

This one needs two things prepared first.

1. The language model as a GGUF file (`Qwen_Qwen3-0.6B-Q4_0.gguf`) from <https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF>.
2. The sound-alike lexicon, built from CMUdict and wordfreq. This step needs a Python environment with `pronouncing`, `wordfreq` and `metaphone` installed.

   ```
   cd tools/context-bench
   PYTHONHASHSEED=0 python build_lexicon.py soundalike-en.lex
   ```

Then package them.

```
python tools/package-context-model.py \
    --gguf Qwen_Qwen3-0.6B-Q4_0.gguf \
    --lexicon tools/context-bench/soundalike-en.lex \
    --out packages/pluto-smart-corrections-en.zip
```

The package also contains `context.json` (the thresholds the rescorer uses) and a `NOTICE.txt` with the licenses.

### Checksums

Once everything's built, it's a good idea to publish checksums next to the files, for example `sha256sum pluto-*.zip > SHA256SUMS.txt`.

## Desktop test tools

There are a few small standalone Gradle projects under `tools/` that run the Kotlin voice code on a PC, so it can be compared with reference Python implementations.

- `tools/moonshine-harness` transcribes a 16 kHz WAV with a Moonshine package.
- `tools/punctuation-harness` runs the punctuation engines and compares them with the Python references (`run_comparison.sh`).
- `tools/context-harness` and `tools/context-bench` hold the Smart corrections rescorer, its benchmark set, and the Python reference.

If you're curious how each feature works, from voice typing to the typing heatmap, [FEATURES.md](FEATURES.md) goes through all of it.

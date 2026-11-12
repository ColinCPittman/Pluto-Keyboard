# Making your own model packages

Pluto Keyboard has no internet permission. Every voice typing, punctuation and Smart corrections model gets onto the phone as a `.zip` "model package" that you import (Voice Input → Set up voice typing → Import, or Languages & Models → a language → Import). The official packages are on the [models page](https://github.com/ColinCPittman/Pluto-Keyboard/releases/tag/models), which also explains which ones to pick. This page walks through the format, so you can build packages from other models too. If you only want to rebuild the official ones, see [BUILDING.md](BUILDING.md#building-the-model-packages).

## The package format

A package is a zip file. At its root it has a `futo-model.json` manifest, plus the model files (flat, or in sub-folders). Put the manifest first in the zip so the importer finds it without reading the whole file.

```json
{
  "format": 1,
  "kind": "punctuation",
  "id": "pcs-en",
  "name": "Punctuation — English",
  "language": "en",
  "engine": "pcs",
  "license": "Apache-2.0",
  "source": "https://huggingface.co/1-800-BAD-CODE/punctuation_fullstop_truecase_english",
  "version": "1"
}
```

| Field | Required | Meaning |
|---|---|---|
| `format` | yes | Always `1`. |
| `kind` | yes | `transcribe` or `moonshine-streaming` (voice typing), `punctuation`, or `context` (Smart corrections). |
| `id` | yes | Lowercase letters, digits, `.`, `_` and `-`, starting with a letter or digit, up to 64 characters. Importing a package with the same `kind` and `id` replaces the installed one, so give your own models their own ids. |
| `name` | yes | What the app shows in its model lists. Falls back to `id`. |
| `language` | no | ISO language code (`en`, `de`, ...), or `multi` for any language. Empty or missing also means any language. Moonshine is only used for English. A `transcribe` model is offered only for keyboard languages that match its `language`. |
| `engine` | depends | Which runtime to use. Needed for `punctuation` (`pcs` or `horizon`) and recommended for `context` (`qwen3-rescore`). |
| `license`, `source`, `version` | no | Shown to nobody yet, but please fill them in so people know where a model came from. |
| `capabilities` | no | Voice typing models only. What the model already does well by itself, so Pluto can skip the fixes it doesn't need. See [What a voice model does by itself](#what-a-voice-model-does-by-itself-capabilities). |

When you import a package, the app checks that the files the engine needs are there. If anything is missing, it won't import the package, and it tells you which file it couldn't find. Installed packages live in the app's private storage under `models/<kind>/<id>/`.

## What Pluto can run

### Voice typing with Nemotron and Parakeet (`kind` is `transcribe`)

Pluto runs NVIDIA's Nemotron and Parakeet speech models with [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp), from the GGUF files that the transcribe.cpp project converts and publishes on [Hugging Face](https://huggingface.co/handy-computer). The package needs one file.

- One `.gguf` model file (exactly one, any name). The app checks that it really is a GGUF file.

Nothing else is needed: the tokenizer and settings are inside the GGUF. Whether a model types while you talk (Nemotron streaming) or after you finish (Parakeet) comes from the model file too. A multilingual model (Nemotron 3.5) is told the keyboard's current language, so set `language` to `multi` for it, and to the model's language (`en`) for an English-only one.

Pluto knows the model family by the start of the package `id` (`nemotron...`, `parakeet...`, and `moonshine...` for a GGUF Moonshine) and uses what it has measured about that family (see the next section). Keep that start if you package another size or quantization of the same family. For those families Pluto's own values always apply and the manifest's `capabilities` are ignored, so packages already imported pick up later tuning. A model with any other `id` is treated as one that needs every fix, unless its manifest says otherwise in `capabilities`.

`tools/package-transcribe-models.py` downloads the official GGUF files and wraps them, together with each model's license and notice. To package another quantization (Q8_0, Q6_K, Q5_K_M or Q4_K_M), change the file name in the script's `MODELS` table. Smaller quantizations load faster and use less memory but make a few more mistakes.

```
python tools/package-transcribe-models.py --models nemotron-en,parakeet-110m --cache models --out packages
```

### What a voice model does by itself (`capabilities`)

Some voice models already punctuate, capitalize, or leave out "um" and "uh". If Pluto ran its own fixes on top, they would only second-guess the model. The optional `capabilities` object in `futo-model.json` says what a model does well. Pluto then skips those fixes, and their settings say that the voice model already handles them.

```json
"capabilities": {"punctuation": "good", "casing": "weak", "pause_segments": true, "streaming": true, "drops_fillers": true}
```

| Key | Values | What it changes |
|---|---|---|
| `punctuation` | `"good"`, `"weak"` or `"none"` | `"good"`: the punctuation model is skipped, and so is Smart corrections' question-mark fix. `"weak"` and `"none"` keep both. |
| `casing` | `"good"`, `"weak"` or `"none"` | `"good"`: Smart corrections leaves the model's capital letters alone. Otherwise it capitalizes names and months from the dictionary. |
| `drops_fillers` | `true` or `false` | `true`: the model leaves out "um", "uh" and words said twice by itself, so Smart corrections' Filler words fix is skipped. |
| `streaming` | `true` or `false` | Whether the voice model list describes it as typing while you talk. (The model file decides how it actually runs.) |
| `pause_segments` | `true` or `false` | Whether the engine reports where you paused. Recorded for future use, nothing reads it yet. |

`capabilities` is read only for a model whose family Pluto doesn't know; for Nemotron, Parakeet and Moonshine it is ignored, and the official packages leave it out. Every key is optional. A key you leave out keeps the "needs every fix" default. Unknown keys and values are ignored. Only claim what you've checked in the model's output yourself.

Only `transcribe` packages read `capabilities` today. `moonshine-streaming` packages always use Pluto's Moonshine values (its own punctuation and capitals are weak, so every fix runs).

### Voice typing with Moonshine streaming models (`kind` is `moonshine-streaming`)

Pluto runs the streaming Moonshine models in the ONNX Runtime `.ort` format, the way the reference [moonshine](https://github.com/moonshine-ai/moonshine) library does. The package needs these files.

- `frontend.model.ort` + `frontend.weights.ort` (or a single `frontend.ort`)
- `encoder.ort`, `adapter.ort`, `cross_kv.ort`, `decoder_kv.ort`
- `streaming_config.json` and `tokenizer.bin`
- `silero_vad.ort` (optional but recommended). This is Silero VAD, used to split long dictation at pauses. Without it, Pluto falls back to a cruder loudness detector.

Use `engine: "onnxruntime"`. Non-streaming Moonshine models and other ONNX speech models won't load. (Pluto also still runs FUTO's Whisper `.bin` models, which are imported as plain files, not as packages.)

`tools/package-models.py` downloads the official Moonshine streaming models (tiny, small and medium, English) and wraps them. To package a different streaming model that has the same files, copy the script and change the `MODELS` table (upstream folder, display name, zip name), or put the files in a folder yourself and zip them with a manifest.

### Punctuation (`kind` is `punctuation`)

Two families of models work.

- `engine: "pcs"` — punctuation, capitalisation and sentence-boundary models in the style of [1-800-BAD-CODE/punctuation_fullstop_truecase_english](https://huggingface.co/1-800-BAD-CODE/punctuation_fullstop_truecase_english). Needs `punct_cap_seg_en.onnx` (any single `.onnx` file works), `spe_32k_lc_en.model` (the SentencePiece tokenizer, or any single `.model` file) and its `config.yaml`.
- `engine: "horizon"` — token-classification models in the style of [Horizon-Labs/punctuation-restoration-small](https://huggingface.co/Horizon-Labs/punctuation-restoration-small). Needs `model_quantized.onnx` (any single `.onnx`), `tokenizer.json` and `config.json`. Files may also sit in an `onnx/` sub-folder.

A model with the same inputs, outputs and label set as one of these will work, but a model with different labels won't. `tools/package-punctuation-models.py` builds both official packages. To package a fine-tuned variant, change the repository names, file names and manifest in its `main()`. `--reduce` (needs `pip install onnx numpy`) makes the ONNX files smaller by storing weights as fp16.

### Smart corrections (`kind` is `context`)

Smart corrections uses a small causal language model as a judge. It scores the finished dictation, and for words that look unlikely, it compares sound-alike candidates and keeps a candidate only if it is clearly more likely. The package needs these files.

- `model.gguf` — any causal language model that [llama.cpp](https://github.com/ggml-org/llama.cpp) can load (any single `.gguf` file works). Pluto ships Qwen3-0.6B quantized to Q4_0. Bigger models can be more accurate but are slower and use more memory, so quantized models (Q4_0, Q4_K_M and so on) are best on a phone.
- `soundalike-en.lex` — the sound-alike lexicon (any single `.lex` file), built by `tools/context-bench/build_lexicon.py` from CMUdict and wordfreq. It is English-only today, and another language would need its own pronunciation data and lexicon.
- `context.json` (optional) — thresholds and runtime settings. Missing keys keep their defaults (see `ContextRescoreConfig` in `voiceinput-shared/.../context/ContextRescorer.kt` for what each one does). The ones you are most likely to change for a different model are `prompt` (text the transcript is scored after), `surprise` (how unlikely a word must be before candidates are tried), `word_margin` (how much better a candidate must score), `threads` and `max_chars`.

Use `engine: "qwen3-rescore"` (any engine name ending in `-rescore` is accepted). Smart corrections needs a 64-bit ARM phone with the dot-product and fp16 instructions (most phones from 2018 on).

`tools/package-context-model.py` packages a GGUF and a lexicon.

```
python tools/package-context-model.py --gguf my-model.gguf --lexicon soundalike-en.lex \
    --id context-my-model-en --name "Smart corrections — My model" --out my-smart-corrections.zip
```

Its `--threads` option sets the default thread count in `context.json`. Edit the `CONFIG` table at the top of the script to change the thresholds for your model, and update the `NOTICE` text and manifest `license`/`source` to match the model you used. `python tools/package-context-model.py --help` lists every option in the version you have.

## Tips

- It's worth testing a new model on the desktop first. `tools/moonshine-harness`, `tools/punctuation-harness` and `tools/context-harness` run the same Kotlin code as the app (see [BUILDING.md](BUILDING.md#desktop-test-tools)). For a `transcribe` model, `tools/voice-bench` runs it with transcribe.cpp's `transcribe-cli` on a few hundred spoken test sentences and scores it with and without Smart corrections, which is a quick way to fill in `capabilities` (see its README).
- Keep each package's `id` stable between versions of the same model, so importing a new version replaces the old one instead of adding a second copy.
- Please respect the model's license and include its license or notice file in the zip.

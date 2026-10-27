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
| `kind` | yes | `moonshine-streaming` (voice typing), `punctuation` or `context` (Smart corrections). |
| `id` | yes | Lowercase letters, digits, `.`, `_` and `-`, starting with a letter or digit, up to 64 characters. Importing a package with the same `kind` and `id` replaces the installed one, so give your own models their own ids. |
| `name` | yes | What the app shows in its model lists. Falls back to `id`. |
| `language` | no | ISO language code (`en`, `de`, ...), or `multi` for any language. Empty or missing also means any language. Moonshine is only used for English. |
| `engine` | depends | Which runtime to use. Needed for `punctuation` (`pcs` or `horizon`) and recommended for `context` (`qwen3-rescore`). |
| `license`, `source`, `version` | no | Shown to nobody yet, but please fill them in so people know where a model came from. |

When you import a package, the app checks that the files the engine needs are there. If anything is missing, it won't import the package, and it tells you which file it couldn't find. Installed packages live in the app's private storage under `models/<kind>/<id>/`.

## What Pluto can run

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

- It's worth testing a new model on the desktop first. `tools/moonshine-harness`, `tools/punctuation-harness` and `tools/context-harness` run the same Kotlin code as the app (see [BUILDING.md](BUILDING.md#desktop-test-tools)).
- Keep each package's `id` stable between versions of the same model, so importing a new version replaces the old one instead of adding a second copy.
- Please respect the model's license and include its license or notice file in the zip.

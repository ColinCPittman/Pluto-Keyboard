#!/usr/bin/env python3
"""
Builds Pluto Keyboard "model package" zips for the Moonshine streaming speech
recognition models (English).

The keyboard has no internet permission, so models are imported from a file the
user picks on their device. This script downloads the official Moonshine
streaming weights (ONNX Runtime .ort format, MIT licensed for English) from
HuggingFace and wraps each model into a zip with a `futo-model.json` manifest:

    futo-model.json            manifest (see below)
    frontend.model.ort         } streaming frontend (split graph + weights)
    frontend.weights.ort       }
    encoder.ort
    adapter.ort
    cross_kv.ort
    decoder_kv.ort
    streaming_config.json
    tokenizer.bin
    silero_vad.ort             Silero VAD (MIT), used to split long dictation
                               into utterances exactly like the reference
                               moonshine library does
    LICENSE.txt

Manifest:
    {"format": 1, "kind": "moonshine-streaming", "id": "...", "name": "...",
     "language": "en", "engine": "onnxruntime", "license": "MIT",
     "source": "<url>", "version": "<upstream version>"}

Usage (stdlib only):
    python tools/package-models.py [--models tiny,small,medium]
        [--cache D:/FUTO-models/cache/moonshine] [--out D:/FUTO-models/packages]

Downloaded files are cached (and verified against the HuggingFace LFS sha256)
so re-running is cheap. The cache directory for each model is itself a valid
model directory, usable directly by tools/moonshine-harness.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import urllib.request
import zipfile

HF_REPO = "moonshine-ai/moonshine-voice-assets"
HF_BASE = f"https://huggingface.co/{HF_REPO}/resolve/main"
HF_API_TREE = f"https://huggingface.co/api/models/{HF_REPO}/tree/main"
UPSTREAM_VERSION = "quantized_26_08_21"

# Silero VAD as converted to .ort by the moonshine project (embedded there as a
# C array). Pinned to the commit the Kotlin port was written against.
MOONSHINE_COMMIT = "234f60faa0eb388b01cdf7e60aca232af37aefda"
SILERO_HEADER_URL = (
    "https://raw.githubusercontent.com/moonshine-ai/moonshine/"
    f"{MOONSHINE_COMMIT}/core/silero-vad-model-data.h"
)

MODEL_FILES = [
    "frontend.model.ort",
    "frontend.weights.ort",
    "encoder.ort",
    "adapter.ort",
    "cross_kv.ort",
    "decoder_kv.ort",
    "streaming_config.json",
    "tokenizer.bin",
]

# key -> (upstream folder, name shown in the app, file name of the release zip)
MODELS = {
    "tiny": ("tiny-streaming-en", "Moonshine Tiny — English", "pluto-voice-moonshine-tiny-en.zip"),
    "small": ("small-streaming-en", "Moonshine Small — English (recommended)", "pluto-voice-moonshine-small-en.zip"),
    "medium": ("medium-streaming-en", "Moonshine Medium — English", "pluto-voice-moonshine-medium-en.zip"),
}

LICENSE_TEXT = """Moonshine streaming speech recognition model (English)
Copyright (c) Moonshine AI / Useful Sensors. Released under the MIT License.
Source: https://huggingface.co/moonshine-ai/moonshine-voice-assets
Runtime reference implementation: https://github.com/moonshine-ai/moonshine (MIT)

silero_vad.ort: Silero VAD, Copyright (c) Silero Team, MIT License
(https://github.com/snakers4/silero-vad), converted to .ort by the moonshine
project.

MIT License: Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including without
limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom
the Software is furnished to do so, subject to the following conditions: The
above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS
IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.
"""


def log(msg):
    print(msg, flush=True)


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def download(url, dest, expected_size=None, expected_sha256=None):
    if os.path.exists(dest):
        ok = expected_size is None or os.path.getsize(dest) == expected_size
        if ok and expected_sha256:
            ok = sha256_of(dest) == expected_sha256
        if ok:
            return
        log(f"  re-downloading {os.path.basename(dest)} (cached copy invalid)")
    tmp = dest + ".part"
    log(f"  downloading {url}")
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    if expected_size is not None and os.path.getsize(tmp) != expected_size:
        raise RuntimeError(f"size mismatch for {url}")
    if expected_sha256 and sha256_of(tmp) != expected_sha256:
        raise RuntimeError(f"sha256 mismatch for {url}")
    os.replace(tmp, dest)


def ensure_silero(cache_root):
    out = os.path.join(cache_root, "silero_vad.ort")
    if os.path.exists(out) and os.path.getsize(out) > 100000:
        return out
    header = os.path.join(cache_root, "silero-vad-model-data.h")
    download(SILERO_HEADER_URL, header)
    with open(header, "r", encoding="utf-8") as f:
        text = f.read()
    start = text.index("silero_vad_onnx[] = {")
    end = text.index("};", start)
    data = bytes(int(h, 16) for h in re.findall(r"0x([0-9a-fA-F]{2})", text[start:end]))
    m = re.search(r"silero_vad_onnx_len\s*=\s*(\d+)", text)
    if m and int(m.group(1)) != len(data):
        raise RuntimeError("silero header length mismatch")
    with open(out, "wb") as f:
        f.write(data)
    os.remove(header)
    return out


def fetch_model(key, cache_root):
    folder = MODELS[key][0]
    remote_dir = f"model/{folder}/{UPSTREAM_VERSION}"
    tree = {e["path"].split("/")[-1]: e for e in fetch_json(f"{HF_API_TREE}/{remote_dir}")}
    local_dir = os.path.join(cache_root, f"moonshine-{folder}")
    os.makedirs(local_dir, exist_ok=True)
    for name in MODEL_FILES:
        if name not in tree:
            raise RuntimeError(f"{name} missing upstream for {key}")
        entry = tree[name]
        sha = (entry.get("lfs") or {}).get("oid")
        download(f"{HF_BASE}/{remote_dir}/{name}", os.path.join(local_dir, name),
                 expected_size=entry.get("size"), expected_sha256=sha)
    shutil.copyfile(ensure_silero(cache_root), os.path.join(local_dir, "silero_vad.ort"))
    return local_dir, f"https://huggingface.co/{HF_REPO}/tree/main/{remote_dir}"


def build_zip(key, local_dir, source, out_dir):
    folder, display_name, zip_name = MODELS[key]
    model_id = f"moonshine-{folder}"
    manifest = {
        "format": 1,
        "kind": "moonshine-streaming",
        "id": model_id,
        "name": display_name,
        "language": "en",
        "engine": "onnxruntime",
        "license": "MIT",
        "source": source,
        "version": UPSTREAM_VERSION,
    }
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, zip_name)
    tmp = out + ".part"
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        # Manifest first so importers can find it quickly.
        z.writestr("futo-model.json", json.dumps(manifest, indent=2) + "\n")
        z.writestr("LICENSE.txt", LICENSE_TEXT)
        for name in MODEL_FILES + ["silero_vad.ort"]:
            z.write(os.path.join(local_dir, name), name)
    os.replace(tmp, out)
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--models", default="tiny,small,medium")
    p.add_argument("--cache", default=os.path.join("D:" + os.sep, "FUTO-models", "cache", "moonshine"))
    p.add_argument("--out", default=os.path.join("D:" + os.sep, "FUTO-models", "packages"))
    p.add_argument("--no-zip", action="store_true", help="only download into the cache")
    args = p.parse_args()

    os.makedirs(args.cache, exist_ok=True)
    for key in [m.strip() for m in args.models.split(",") if m.strip()]:
        if key not in MODELS:
            sys.exit(f"unknown model {key}; choose from {', '.join(MODELS)}")
        log(f"[{key}] fetching")
        local_dir, source = fetch_model(key, args.cache)
        if args.no_zip:
            log(f"[{key}] cached in {local_dir}")
            continue
        out = build_zip(key, local_dir, source, args.out)
        log(f"[{key}] wrote {out} ({os.path.getsize(out) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()

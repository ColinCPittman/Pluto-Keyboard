#!/usr/bin/env python3
"""
Builds Pluto Keyboard "model package" zips for the transcribe.cpp voice models
(NVIDIA Nemotron streaming and Parakeet), kind "transcribe".

The keyboard has no internet permission, so models are imported from a file the
user picks on their device. This script downloads the GGUF conversions published
by the transcribe.cpp project (huggingface.co/handy-computer, the same files the
engine is tested against) and wraps each into a zip:

    futo-model.json      manifest (see below)
    <model>.gguf         the model (stored uncompressed, GGUF doesn't compress)
    LICENSE.txt          the model's license, from the Hugging Face repo
    NOTICE.txt           the repo's NOTICE, when it has one

Manifest:
    {"format": 1, "kind": "transcribe", "id": "...", "name": "...",
     "language": "en" | "multi", "engine": "transcribe.cpp", "license": "...",
     "source": "<url>", "version": "<file name>"}

No "capabilities": the app picks the family's profile from the id prefix
(TranscribeModelFiles.profile: "nemotron..." -> VoiceModelProfile.NEMOTRON,
"parakeet..." -> PARAKEET) and ignores "capabilities" for those families, so
later tuning of the profiles in the app reaches packages already imported. Add
"capabilities" only for a model whose family the app doesn't know (docs/MODELS.md).

Usage (stdlib only):
    python tools/package-transcribe-models.py [--models nemotron-en,parakeet-110m]
        [--cache C:/dev/models] [--out C:/dev/models/packages]

Downloads are cached (and verified against the Hugging Face LFS sha256), so
re-running is cheap.
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.request
import zipfile

HF = "https://huggingface.co"

# key -> (repo, file, package id, name shown in the app, language, license, release zip).
# The package id must keep its family prefix ("nemotron", "parakeet"), see the docstring.
MODELS = {
    "nemotron-en": (
        "handy-computer/nemotron-speech-streaming-en-0.6b-gguf",
        "nemotron-speech-streaming-en-0.6b-Q4_K_M.gguf",
        "nemotron-speech-streaming-en-0.6b",
        "Nemotron Streaming — English",
        "en",
        "NVIDIA Open Model License",
        "pluto-voice-nemotron-en.zip",
    ),
    "nemotron-multi": (
        "handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf",
        "nemotron-3.5-asr-streaming-0.6b-Q4_K_M.gguf",
        "nemotron-3.5-asr-streaming-0.6b",
        "Nemotron 3.5 Streaming — Multilingual",
        "multi",
        "See LICENSE.txt",
        "pluto-voice-nemotron-multilingual.zip",
    ),
    "parakeet-110m": (
        "handy-computer/parakeet-tdt_ctc-110m-gguf",
        "parakeet-tdt_ctc-110m-Q8_0.gguf",
        "parakeet-tdt_ctc-110m",
        "Parakeet 110M — English (fast)",
        "en",
        "CC-BY-4.0",
        "pluto-voice-parakeet-110m-en.zip",
    ),
}
FAMILIES = ("nemotron", "parakeet", "moonshine")  # TranscribeModelFiles.profile


def tree(repo):
    with urllib.request.urlopen(f"{HF}/api/models/{repo}/tree/main") as r:
        return {e["path"]: e for e in json.load(r)}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch(repo, name, dest, expected_sha=None):
    """Downloads repo/name to dest unless a verified copy is already there."""
    if os.path.isfile(dest) and (expected_sha is None or sha256(dest) == expected_sha):
        return dest
    print(f"  downloading {name} ...", flush=True)
    tmp = dest + ".part"
    urllib.request.urlretrieve(f"{HF}/{repo}/resolve/main/{name}", tmp)
    if expected_sha is not None and sha256(tmp) != expected_sha:
        os.remove(tmp)
        sys.exit(f"sha256 mismatch for {name}")
    os.replace(tmp, dest)
    return dest


def package(key, cache, out):
    repo, gguf, pkg_id, name, language, license_name, zip_name = MODELS[key]
    assert pkg_id.startswith(FAMILIES), f"{pkg_id}: the app would not know its family"
    print(f"{key}: {repo}/{gguf}")
    files = tree(repo)
    if gguf not in files:
        sys.exit(f"{repo} has no {gguf}; files: {', '.join(sorted(files))}")
    lfs = files[gguf].get("lfs") or {}
    model = fetch(repo, gguf, os.path.join(cache, gguf), lfs.get("oid"))
    extras = {}
    for upstream, packaged in (("LICENSE", "LICENSE.txt"), ("NOTICE", "NOTICE.txt")):
        if upstream in files:
            extras[packaged] = fetch(repo, upstream, os.path.join(cache, f"{repo.split('/')[1]}-{upstream}"))
    if "LICENSE.txt" not in extras:
        print(f"  warning: {repo} has no LICENSE file, the package will carry none")

    manifest = {
        "format": 1,
        "kind": "transcribe",
        "id": pkg_id,
        "name": name,
        "language": language,
        "engine": "transcribe.cpp",
        "license": license_name,
        "source": f"{HF}/{repo}",
        "version": gguf,
    }
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, zip_name)
    with zipfile.ZipFile(path + ".part", "w") as z:
        z.writestr("futo-model.json", json.dumps(manifest, indent=2, ensure_ascii=False), zipfile.ZIP_DEFLATED)
        z.write(model, gguf, zipfile.ZIP_STORED)
        for packaged, src in extras.items():
            z.write(src, packaged, zipfile.ZIP_DEFLATED)
    os.replace(path + ".part", path)
    print(f"  -> {path} ({os.path.getsize(path) / 1e6:.0f} MB)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models", default="nemotron-en,parakeet-110m", help="comma list of: " + ", ".join(MODELS))
    ap.add_argument("--cache", default="C:/dev/models")
    ap.add_argument("--out", default="C:/dev/models/packages")
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    for key in [k.strip() for k in args.models.split(",") if k.strip()]:
        if key not in MODELS:
            sys.exit(f"unknown model '{key}', expected one of {', '.join(MODELS)}")
        package(key, args.cache, args.out)


if __name__ == "__main__":
    main()

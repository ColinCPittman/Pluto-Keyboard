#!/usr/bin/env python3
"""Builds the Pluto Keyboard voice-input context model ("Smart corrections") package (zip with a futo-model.json manifest at the root).

stdlib only. Inputs are local files: the GGUF (llama.cpp format) and the sound-alike lexicon made by
tools/context-bench/build_lexicon.py (that script needs the context-bench venv; this one doesn't).

Zip contents (flat, see ContextModelFiles.kt):
    futo-model.json    kind "context", engine "qwen3-rescore", language "en"
    model.gguf         the judging LM (stored uncompressed so extraction is a plain copy)
    soundalike-en.lex  sound-alike candidates, word boundaries and case data (format 2; apps before
                       the Smart corrections options read only format 1, see build_lexicon.py --v1)
    context.json       thresholds (defaults of tools/context-bench/rescore.py) and runtime settings
    NOTICE.txt         licenses (Qwen3 Apache-2.0, CMUdict, wordfreq data CC-BY-SA 4.0, AOSP word list Apache-2.0)

usage:
    python package-context-model.py --gguf Qwen_Qwen3-0.6B-Q4_0.gguf --lexicon soundalike-en.lex \\
        [--out pluto-smart-corrections-en.zip] [--id context-qwen3-0.6b-en] [--name "..."] [--threads 2]
"""
import argparse
import hashlib
import json
import os
import zipfile

# Same values as rescore.py's defaults (the design measured on the phone as 46/46 exact).
CONFIG = {
    "prompt": "Transcript of a casual voice message:\n",
    "surprise": -4.0,
    "max_words": 8,
    "k": 6,
    "probe_margin": 3.0,
    "tail_words": 6,
    "word_margin": 2.0,
    "q_margin": 0.0,
    "tag_margin": 0.0,
    "follow_margin": -3.0,
    "tag_words": ["right", "okay", "ok", "yeah", "correct", "huh", "no", "yes", "really"],
    # Smart corrections options (ContextRescoreConfig, tuned on tools/context-bench/testset_*.jsonl)
    "max_extra_slots": 6,
    "boundary_margin": 3.0,
    "split_surprise": -9.0,
    "name_margin": 3.0,
    "name_swap_margin": 0.0,
    "case_margin": 2.0,
    "filler_margin": 0.0,
    "repeat_margin": 3.0,
    "self_correction_margin": -2.0,
    "scratch_margin": 0.0,
    "command_margin": 0.0,
    "line_break_margin": -8.0,
    "number_margin": 0.0,
    "number_single_word_margin": 5.0,
    "threads": 2,
    "repack": True,
    "max_chars": 1500,
}

NOTICE = """Pluto Keyboard smart corrections package: {name}

1. Language model: Qwen3-0.6B, quantized to GGUF Q4_0
   Original model: https://huggingface.co/Qwen/Qwen3-0.6B (Copyright Alibaba Cloud / Qwen team)
   GGUF conversion: https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF
   License: Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
   The weights are redistributed unmodified ({gguf_name}, sha256 {sha}).

2. Sound-alike lexicon (soundalike-en.lex)
   Derived from:
   a) The CMU Pronouncing Dictionary (CMUdict), via the Python `pronouncing`/`cmudict` packages.
      Copyright (C) 1993-2015 Carnegie Mellon University. All rights reserved.
      Redistribution and use in source and binary forms, with or without modification, are
      permitted provided that the following conditions are met:
      1. Redistributions of source code must retain the above copyright notice, this list of
         conditions and the following disclaimer. The contents of this file are deemed to be
         source code.
      2. Redistributions in binary form must reproduce the above copyright notice, this list of
         conditions and the following disclaimer in the documentation and/or other materials
         provided with the distribution.
      This work was supported in part by funding from the Defense Advanced Research Projects
      Agency, the Office of Naval Research and the National Science Foundation of the United
      States of America, and by member companies of the Carnegie Mellon Sphinx Speech Consortium.
      THIS SOFTWARE IS PROVIDED BY CARNEGIE MELLON UNIVERSITY ``AS IS'' AND ANY EXPRESSED OR
      IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
      AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL CARNEGIE MELLON
      UNIVERSITY NOR ITS EMPLOYEES BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
      EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
      GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
      ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
      NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
      OF THE POSSIBILITY OF SUCH DAMAGE.
   b) Word frequencies from wordfreq 3.1 by Robyn Speer (https://github.com/rspeer/wordfreq),
      used to choose and rank the candidate words. wordfreq's data is licensed under the Creative
      Commons Attribution-ShareAlike 4.0 license (https://creativecommons.org/licenses/by-sa/4.0/)
      and includes data from Google Books Ngrams, SUBTLEX, OpenSubtitles, Wikipedia, Reddit,
      Twitter, the Leeds Internet Corpus and others (see the wordfreq README for the full list).
      Accordingly, soundalike-en.lex is distributed under CC-BY-SA 4.0.
   c) Case data (which words are usually capitalized) and the word list for word-boundary
      candidates from the Android Open Source Project's English word list
      (dictionaries/en_US_wordlist.combined.gz in the Pluto Keyboard / FUTO Keyboard source),
      Copyright (C) The Android Open Source Project, Apache License 2.0
      (https://www.apache.org/licenses/LICENSE-2.0).
   The candidate rules (same phoneme count, at most one phoneme different, Double Metaphone
   agreement; word boundaries: identical CMUdict pronunciation) come from this repository's
   tools/context-bench (rescore.py, build_lexicon.py).
"""


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gguf", required=True)
    ap.add_argument("--lexicon", required=True)
    ap.add_argument("--out", default="pluto-smart-corrections-en.zip")
    ap.add_argument("--id", default="context-qwen3-0.6b-en")
    ap.add_argument("--name", default="Smart corrections — English")
    ap.add_argument("--threads", type=int, default=2)
    args = ap.parse_args()

    with open(args.gguf, "rb") as f:
        if f.read(4) != b"GGUF":
            raise SystemExit(f"{args.gguf} is not a GGUF file")
    with open(args.lexicon, "rb") as f:
        if f.read(8) not in (b"FCXLEX01", b"FCXLEX02"):
            raise SystemExit(f"{args.lexicon} is not a sound-alike lexicon (build_lexicon.py)")

    manifest = {
        "format": 1,
        "kind": "context",
        "id": args.id,
        "name": args.name,
        "language": "en",
        "engine": "qwen3-rescore",
        "license": "Apache-2.0 (model), CC-BY-SA-4.0 (lexicon)",
        "source": "https://huggingface.co/Qwen/Qwen3-0.6B",
        "version": "2",
    }
    config = dict(CONFIG, threads=args.threads)
    notice = NOTICE.format(name=args.name, gguf_name=os.path.basename(args.gguf), sha=sha256(args.gguf))

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        # manifest first so the importer finds it without reading the whole zip
        z.writestr("futo-model.json", json.dumps(manifest, indent=2) + "\n")
        z.writestr("context.json", json.dumps(config, indent=2) + "\n")
        z.writestr("NOTICE.txt", notice)
        z.write(args.lexicon, "soundalike-en.lex")
        # quantized weights barely compress, so store them
        z.write(args.gguf, "model.gguf", compress_type=zipfile.ZIP_STORED)
    print(f"wrote {args.out} ({os.path.getsize(args.out) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()

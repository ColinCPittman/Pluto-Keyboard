# Third-party notices (Pluto Keyboard additions)

Pluto Keyboard is a modified version of FUTO Keyboard (FUTO Source First License 1.1,
see `LICENSE.md`; AOSP LatinIME notices are in `NOTICE`). This file lists the
third-party code and models that Pluto Keyboard adds on top of that. The model
packages are separate downloads (see the GitHub release); each package carries its
own license text, and the licenses are summarised here:

| Component | Used for | License |
|---|---|---|
| Moonshine (code port and English streaming models) | Voice typing | MIT |
| Silero VAD | Splitting speech into utterances | MIT |
| ONNX Runtime | Running the voice and punctuation models | MIT |
| llama.cpp / ggml | Running the Smart corrections model | MIT |
| Qwen3-0.6B (GGUF Q4_0) | Smart corrections language model | Apache-2.0 |
| pcs_en punctuation model (1-800-BAD-CODE) | Punctuation, English | Apache-2.0 |
| CMU Pronouncing Dictionary | Sound-alike lexicon | BSD-style (CMU) |
| wordfreq data (Robyn Speer) | Sound-alike lexicon ranking | CC-BY-SA 4.0 |
| Double Metaphone (Python `metaphone` port) | Sound-alike matching | BSD-style |
| gemoji emoji list (`java/res/raw/gemoji.json`, from FUTO Keyboard) | Emoji by name in voice typing | MIT |
| Unicode CLDR emoji names (via gemoji) | Emoji by name in voice typing | Unicode License v3 |

Whisper (the other voice typing engine) and the rest of the keyboard come from FUTO
Keyboard and are covered by its license.

## Moonshine (speech recognition)

`voiceinput-shared/src/main/java/org/futo/voiceinput/shared/moonshine/core/`
is a Kotlin port of parts of the Moonshine Voice C++ core
(<https://github.com/moonshine-ai/moonshine>, tag v0.1.5:
`core/moonshine-streaming-model.cpp`, `core/transcriber.cpp`,
`core/voice-activity-detector.cpp`, `core/silero-vad.cpp`,
`core/bin-tokenizer/bin-tokenizer.cpp`).

    MIT License
    Copyright (c) 2024-2026 Moonshine AI (Useful Sensors Inc.)

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

The model weights are not part of this repository. They are imported by the
user as model packages built by `tools/package-models.py` from
<https://huggingface.co/moonshine-ai/moonshine-voice-assets> (English
speech-to-text models: MIT). The packages also contain Silero VAD
(<https://github.com/snakers4/silero-vad>, MIT, Copyright (c) Silero Team).

## ONNX Runtime

`com.microsoft.onnxruntime:onnxruntime-android` (MIT License, Copyright (c)
Microsoft Corporation), <https://github.com/microsoft/onnxruntime>.

## llama.cpp / ggml (voice-input context model)

`voiceinput-shared/src/main/cpp/llama.cpp` is a git submodule of
<https://github.com/ggml-org/llama.cpp>, pinned to commit
`4c3c5810f87efe177949f1b85ae509d38a2a311f`. It is compiled (CPU backend only,
statically, hidden symbols) into `libfutoctxlm.so` together with
`voiceinput-shared/src/main/cpp/contextlm/`, which is adapted from
`tools/context-bench/ctxrun/ctxrun.cpp`.

    MIT License
    Copyright (c) 2023-2026 The ggml authors

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

The context model weights (Qwen3-0.6B, Apache-2.0) and the sound-alike lexicon
(derived from CMUdict, BSD-style, and wordfreq data, CC-BY-SA 4.0) are not part
of this repository; they are imported by the user as a model package built by
`tools/package-context-model.py`, whose NOTICE.txt carries their licenses.

## Punctuation models

The optional punctuation package contains, unmodified apart from storage changes
(unused embedding rows dropped, weights stored as fp16 and converted back at load time):

- `pcs_en`, from <https://huggingface.co/1-800-BAD-CODE/punctuation_fullstop_truecase_english>
  (Apache-2.0), used for English.

Its license is the Apache License 2.0 (<https://www.apache.org/licenses/LICENSE-2.0>);
the package NOTICE.txt file points to the source. The model weights are not part of
this repository.

## Double Metaphone (Python `metaphone` 0.6)

`voiceinput-shared/src/main/java/org/futo/voiceinput/shared/context/DoubleMetaphone.kt`
is a Kotlin port of `metaphone/metaphone.py` (<https://github.com/oubiwann/metaphone>).

    Copyright (c) 2007 Andrew Collins, Chris Leong
    Copyright (c) 2009 Matthew Somerville
    Copyright (c) 2010 Maximillian Dornseif, Richard Barran
    Copyright (c) 2012 Duncan McGreggor
    All rights reserved.

     * Redistribution and use in source and binary forms, with or without
       modification, are permitted provided that the following conditions are met:

     * Redistributions of source code must retain the above copyright notice, this
       list of conditions and the following disclaimer.

     * Redistributions in binary form must reproduce the above copyright notice,
       this list of conditions and the following disclaimer in the documentation
       and/or other materials provided with the distribution.

    Neither the name "Metaphone" nor the names of its contributors may be used to
    endorse or promote products derived from this software without specific prior
    written permission.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
    ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
    WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
    DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
    FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
    DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
    SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
    CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
    OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
    OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Emoji names (gemoji and Unicode CLDR)

Emoji by name in voice typing ("thumbs up emoji") reads its built-in names from
`java/res/raw/gemoji.json`, the gemoji list that FUTO Keyboard already ships for emoji
search (<https://github.com/github/gemoji>). The names in it are the Unicode CLDR emoji
names (<https://cldr.unicode.org/>).

gemoji is under the MIT License:

    Copyright (c) 2019 GitHub, Inc.

    Permission is hereby granted, free of charge, to any person obtaining a copy of
    this software and associated documentation files (the "Software"), to deal in
    the Software without restriction, including without limitation the rights to
    use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
    the Software, and to permit persons to whom the Software is furnished to do so,
    subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
    FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
    COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
    IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
    CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

The Unicode CLDR data is under the Unicode License v3
(<https://www.unicode.org/license.txt>):

    UNICODE LICENSE V3

    COPYRIGHT AND PERMISSION NOTICE

    Copyright © 1991-2024 Unicode, Inc.

    NOTICE TO USER: Carefully read the following legal agreement. BY DOWNLOADING,
    INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR SOFTWARE, YOU
    UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE TERMS AND CONDITIONS
    OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT DOWNLOAD, INSTALL, COPY,
    DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

    Permission is hereby granted, free of charge, to any person obtaining a copy of
    data files and any associated documentation (the "Data Files") or software and
    any associated documentation (the "Software") to deal in the Data Files or
    Software without restriction, including without limitation the rights to use,
    copy, modify, merge, publish, distribute, and/or sell copies of the Data Files
    or Software, and to permit persons to whom the Data Files or Software are
    furnished to do so, provided that either (a) this copyright and permission
    notice appear with all copies of the Data Files or Software, or (b) this
    copyright and permission notice appear in associated Documentation.

    THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
    EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
    MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF THIRD
    PARTY RIGHTS.

    IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE BE
    LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES, OR ANY
    DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
    ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
    CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA FILES OR SOFTWARE.

    Except as contained in this notice, the name of a copyright holder shall not be
    used in advertising or otherwise to promote the sale, use or other dealings in
    these Data Files or Software without prior written authorization of the
    copyright holder.

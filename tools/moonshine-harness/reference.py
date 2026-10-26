#!/usr/bin/env python3
"""
Reference transcription with the official `moonshine-voice` Python package
(pip install moonshine-voice), used to check the Kotlin port in this harness.

    python reference.py <model-dir> <wav> [--arch small|medium|tiny] [--chunk-ms 160]

Prints the non-streaming transcript (VAD segments, each decoded once) and the
final transcript of a streaming run fed in fixed-size chunks.
"""
import argparse
import time
import wave
import array

from moonshine_voice.transcriber import Transcriber
from moonshine_voice.moonshine_api import ModelArch


def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2
        pcm = array.array("h", w.readframes(w.getnframes()))
    return [s / 32768.0 for s in pcm]


def lines_text(transcript):
    return " ".join(l.text.strip() for l in transcript.lines if l.text and l.text.strip())


def main():
    p = argparse.ArgumentParser()
    p.add_argument("model_dir")
    p.add_argument("wav")
    p.add_argument("--arch", default="small")
    p.add_argument("--chunk-ms", type=int, default=160)
    args = p.parse_args()
    arch = {"tiny": ModelArch.TINY_STREAMING, "small": ModelArch.SMALL_STREAMING,
            "medium": ModelArch.MEDIUM_STREAMING}[args.arch]
    audio = read_wav(args.wav)
    t = Transcriber(args.model_dir, arch)

    t0 = time.perf_counter()
    tr = t.transcribe_without_streaming(audio, 16000)
    dt = time.perf_counter() - t0
    print("== final (non-streaming)")
    for l in tr.lines:
        print(f"  [{l.start_time:6.2f} +{l.duration:5.2f}] {l.text}")
    print(f"TEXT: {lines_text(tr)}")
    print(f"time {dt:.2f}s audio {len(audio)/16000:.2f}s RTF {dt/(len(audio)/16000):.3f}")

    print("== streaming")
    stream = t.create_stream(0.5)
    stream.start()
    chunk = 16 * args.chunk_ms
    t0 = time.perf_counter()
    last = None
    for i in range(0, len(audio), chunk):
        stream.add_audio(audio[i:i + chunk], 16000)
        txt = lines_text(stream.update_transcription())
        if txt != last:
            print(f"  {i/16000:6.2f}s | {txt}")
            last = txt
    stream.stop()
    tr = stream.update_transcription()
    dt = time.perf_counter() - t0
    print(f"TEXT: {lines_text(tr)}")
    print(f"time {dt:.2f}s")


if __name__ == "__main__":
    main()

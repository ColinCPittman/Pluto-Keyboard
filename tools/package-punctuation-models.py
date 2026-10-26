#!/usr/bin/env python3
"""Builds the Pluto Keyboard punctuation-restoration model packages (zip files with a futo-model.json manifest at the root).

stdlib only. Downloads the original files from Hugging Face (cached in --work), optionally shrinks the ONNX
files (--reduce, runs tools/reduce-onnx-size.py with --python, which needs `onnx` and `numpy`), then writes

    <out>/pluto-punctuation-en.zip            engine "pcs"      (English, ~52M params)
    <out>/pluto-punctuation-multilingual.zip engine "horizon"  (multilingual, ~140M params)

Zip contents (flat, what PunctuationRestorers.load(engine, dir) needs):
    pcs:     futo-model.json, punct_cap_seg_en.onnx, spe_32k_lc_en.model, config.yaml, NOTICE.txt
    horizon: futo-model.json, model_quantized.onnx,  tokenizer.json,      config.json, NOTICE.txt

usage:
    python package-punctuation-models.py [--work DIR] [--out DIR] [--reduce] [--python PYTHON] [--only pcs|horizon]
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.request
import zipfile

HF = "https://huggingface.co"
PCS_REPO = "1-800-BAD-CODE/punctuation_fullstop_truecase_english"
HORIZON_REPO = "Horizon-Labs/punctuation-restoration-small"

HERE = os.path.dirname(os.path.abspath(__file__))


def download(repo, path, dest):
    if os.path.isfile(dest) and os.path.getsize(dest) > 0:
        print(f"  cached   {os.path.basename(dest)}")
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    url = f"{HF}/{repo}/resolve/main/{path}"
    print(f"  download {url}")
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "futo-package-punctuation-models"})
    with urllib.request.urlopen(req) as r, open(tmp, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    os.replace(tmp, dest)
    return dest


def reduce_onnx(python, src, dst, *extra):
    if os.path.isfile(dst) and os.path.getmtime(dst) >= os.path.getmtime(src):
        print(f"  cached   {os.path.basename(dst)} (reduced)")
        return dst
    print(f"  reducing {os.path.basename(src)} -> {dst}")
    subprocess.run([python, os.path.join(HERE, "reduce-onnx-size.py"), src, dst, *extra], check=True)
    return dst


def notice(name, repo, license_name):
    return (f"{name}\n\nSource: {HF}/{repo}\nLicense: {license_name}\n\n"
            "The model weights are redistributed unmodified apart from lossless-in-practice storage changes "
            "(unused embedding rows dropped, weights stored as fp16, converted back to fp32 at load time).\n"
            "The license text is available at the source URL above.\n")


def build_zip(out_path, manifest, files):
    """files: list of (arcname, source path or bytes)"""
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        z.writestr("futo-model.json", json.dumps(manifest, indent=2) + "\n")
        for arc, src in files:
            if isinstance(src, bytes):
                z.writestr(arc, src)
            else:
                z.write(src, arc)
    print(f"  wrote {out_path}  ({os.path.getsize(out_path) / 1e6:.1f} MB)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default=r"D:\FUTO-models\punctuation\_download")
    ap.add_argument("--out", default=r"D:\FUTO-models\packages")
    ap.add_argument("--reduce", action="store_true", help="shrink the ONNX files (fp16 weights, drop unused rows)")
    ap.add_argument("--python", default=sys.executable, help="python with onnx+numpy installed, used for --reduce")
    ap.add_argument("--only", choices=["pcs", "horizon"])
    a = ap.parse_args()

    if a.only in (None, "pcs"):
        print("pcs-en")
        d = os.path.join(a.work, "pcs_en")
        onnx = download(PCS_REPO, "punct_cap_seg_en.onnx", os.path.join(d, "punct_cap_seg_en.onnx"))
        spm = download(PCS_REPO, "spe_32k_lc_en.model", os.path.join(d, "spe_32k_lc_en.model"))
        cfg = download(PCS_REPO, "config.yaml", os.path.join(d, "config.yaml"))
        if a.reduce:
            onnx = reduce_onnx(a.python, onnx, os.path.join(d, "reduced", "punct_cap_seg_en.onnx"),
                               "--embed", "fp16", "--matmul", "fp16")
        manifest = {
            "format": 1, "kind": "punctuation", "id": "pcs-en",
            "name": "Punctuation — English", "language": "en", "engine": "pcs",
            "license": "Apache-2.0", "source": f"{HF}/{PCS_REPO}",
        }
        build_zip(os.path.join(a.out, "pluto-punctuation-en.zip"), manifest, [
            ("punct_cap_seg_en.onnx", onnx), ("spe_32k_lc_en.model", spm), ("config.yaml", cfg),
            ("NOTICE.txt", notice("punctuation_fullstop_truecase_english (pcs_en) by 1-800-BAD-CODE", PCS_REPO, "Apache-2.0").encode()),
        ])

    if a.only in (None, "horizon"):
        print("horizon-small")
        d = os.path.join(a.work, "horizon_small")
        onnx = download(HORIZON_REPO, "onnx/model_quantized.onnx", os.path.join(d, "onnx", "model_quantized.onnx"))
        tok = download(HORIZON_REPO, "tokenizer.json", os.path.join(d, "tokenizer.json"))
        cfg = download(HORIZON_REPO, "config.json", os.path.join(d, "config.json"))
        if a.reduce:
            onnx = reduce_onnx(a.python, onnx, os.path.join(d, "reduced", "model_quantized.onnx"),
                               "--embed", "none", "--matmul", "fp16")
        manifest = {
            "format": 1, "kind": "punctuation", "id": "horizon-small",
            "name": "Punctuation — many languages", "language": "multi", "engine": "horizon",
            "license": "Apache-2.0", "source": f"{HF}/{HORIZON_REPO}",
        }
        build_zip(os.path.join(a.out, "pluto-punctuation-multilingual.zip"), manifest, [
            ("model_quantized.onnx", onnx), ("tokenizer.json", tok), ("config.json", cfg),
            ("NOTICE.txt", notice("punctuation-restoration-small by Horizon-Labs", HORIZON_REPO, "Apache-2.0").encode()),
        ])


if __name__ == "__main__":
    main()

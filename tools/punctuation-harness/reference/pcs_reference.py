"""Reference outputs for the 'pcs' engine using the `punctuators` package (PunctCapSegModelONNX).
Input words: ASR punctuation stripped from edges and lowercased (exactly what the Kotlin engine feeds the model).
usage: python pcs_reference.py MODEL_DIR INPUTS OUTPUT
"""
import sys
from ref_common import strip_asr, read_inputs
from punctuators.models.punc_cap_seg_model import PunctCapSegConfigONNX, PunctCapSegModelONNX

model_dir, inp, outp = sys.argv[1:4]
cfg = PunctCapSegConfigONNX(directory=model_dir, spe_filename="spe_32k_lc_en.model",
                            model_filename="punct_cap_seg_en.onnx", config_filename="config.yaml")
m = PunctCapSegModelONNX(cfg, ort_providers=["CPUExecutionProvider"])
lines = read_inputs(inp)
with open(outp, "w", encoding="utf8") as f:
    for l in lines:
        text = " ".join(strip_asr(l)).lower()
        sents = m.infer([text])[0]
        f.write(" ".join(sents) + "\n")
print(len(lines), "reference outputs written to", outp)

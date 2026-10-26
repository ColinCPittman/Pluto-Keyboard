"""Reference outputs for the 'horizon' engine: the model card's punctuate.py algorithm (tokenizer offsets, 400/50
chunking, label of the token ending each word), run on the quantized ONNX file via onnxruntime.
Input: ASR punctuation stripped from word edges and lowercased (what the Kotlin engine feeds by default).
usage: python horizon_reference.py MODEL_DIR INPUTS OUTPUT [--cased] [--fp32-hf]
  --cased : do not lowercase the input
  --fp32-hf : use the original PyTorch fp32 weights from the HF hub instead of the quantized ONNX (quality check)
"""
import sys
import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer
from ref_common import strip_asr, read_inputs

args = [a for a in sys.argv[1:] if not a.startswith("--")]
flags = {a for a in sys.argv[1:] if a.startswith("--")}
model_dir, inp, outp = args[:3]
tok = AutoTokenizer.from_pretrained(model_dir)
LABELS = ["0", ".", ",", "?", "-", ":"]

if "--fp32-hf" in flags:
    import torch
    from transformers import AutoModelForTokenClassification
    pt = AutoModelForTokenClassification.from_pretrained("Horizon-Labs/punctuation-restoration-small").eval()
    def logits_fn(ids):
        with torch.no_grad():
            return pt(input_ids=torch.tensor([ids])).logits[0].numpy()
else:
    sess = ort.InferenceSession(model_dir + "/onnx/model_quantized.onnx", providers=["CPUExecutionProvider"])
    def logits_fn(ids):
        a = np.array([ids], dtype=np.int64)
        return sess.run(None, {"input_ids": a, "attention_mask": np.ones_like(a)})[0][0]

CHUNK, OVERLAP = 400, 50

def labels(text):
    enc = tok(text, return_offsets_mapping=True, add_special_tokens=False)
    ids, offs = enc["input_ids"], enc["offset_mapping"]
    out, start = {}, 0
    while start < len(ids):
        end = min(len(ids), start + CHUNK)
        x = [tok.cls_token_id] + ids[start:end] + [tok.sep_token_id]
        pred = logits_fn(x)[1:-1].argmax(-1).tolist()
        keep_to = end if end == len(ids) else end - OVERLAP
        for k in range(start, keep_to):
            s, e = offs[k]
            if e > s:
                out[e] = LABELS[pred[k - start]]
        if end == len(ids):
            break
        start = keep_to
    return out

def punctuate(text):
    labs = labels(text)
    res, prev = [], 0
    for e in sorted(labs):
        lab = labs[e]
        if lab == "0" or not (e == len(text) or text[e] == " "):
            continue
        res.append(text[prev:e] + (" -" if lab == "-" else lab)); prev = e
    return "".join(res) + text[prev:]

lines = read_inputs(inp)
with open(outp, "w", encoding="utf8") as f:
    for l in lines:
        text = " ".join(strip_asr(l))
        if "--cased" not in flags:
            text = text.lower()
        f.write(punctuate(text) + "\n")
print(len(lines), "reference outputs written to", outp)

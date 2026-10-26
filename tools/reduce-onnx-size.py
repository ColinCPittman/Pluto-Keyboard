"""Lossless-in-practice size reduction of fp32 ONNX files (needs: pip install onnx numpy; the int8 modes also onnxruntime).

 --embed  (PCS only: bert_model.embeddings.word_embeddings.weight)
    The table has 64000 rows but the SentencePiece model has only 32000 ids: the extra rows are always dropped.
    fp16 : store the 32000 rows as fp16 + Cast to fp32 after the Gather          [default]
    fp32 : only drop the unused rows
    int8 : per-row int8 + scale (smaller, but ~0.2% of words differ from fp32 in my tests)
    none : leave the embeddings alone (use for other models, e.g. Horizon whose table is already int8)
 --matmul
    fp16 : store every 2-D fp32 MatMul weight as fp16 + Cast to fp32            [default]
    int8 : onnxruntime dynamic int8 quantization (visible quality loss on PCS, not used)
    none

All math still runs in fp32 (ORT constant-folds the Casts at load time), so outputs match the fp32 model to ~1e-3.

usage: python reduce-onnx-size.py IN.onnx OUT.onnx [--embed fp16|fp32|int8|none] [--matmul fp16|int8|none]
"""
import sys, os, tempfile
import numpy as np
import onnx
from onnx import numpy_helper, helper, TensorProto

args = [a for a in sys.argv[1:] if not a.startswith("--")]
opt = {sys.argv[i]: sys.argv[i + 1] for i in range(1, len(sys.argv) - 1) if sys.argv[i].startswith("--")}
src, dst = args[:2]
os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
embed_mode = opt.get("--embed", "fp16")
matmul_mode = opt.get("--matmul", "fp16")
EMB = "bert_model.embeddings.word_embeddings.weight"
ROWS = 32000

m = onnx.load(src)
g = m.graph
if embed_mode != "none":

    init = {i.name: i for i in g.initializer}
    emb = numpy_helper.to_array(init[EMB])[:ROWS].copy()
    consumers = [n for n in g.node if EMB in n.input]
    assert len(consumers) == 1 and consumers[0].op_type == "Gather", [c.op_type for c in consumers]
    gather = consumers[0]
    g.initializer.remove(init[EMB])

    if embed_mode == "fp32":
        g.initializer.append(numpy_helper.from_array(emb, EMB))
    elif embed_mode == "fp16":
        g.initializer.append(numpy_helper.from_array(emb.astype(np.float16), EMB + "_fp16"))
        out = gather.output[0]
        gather.input[0] = EMB + "_fp16"
        gather.output[0] = out + "_fp16"
        idx = list(g.node).index(gather)
        g.node.insert(idx + 1, helper.make_node("Cast", [out + "_fp16"], [out], to=TensorProto.FLOAT, name="emb_cast"))
    elif embed_mode == "int8":
        scale = np.maximum(np.abs(emb).max(axis=1), 1e-8) / 127.0
        q = np.clip(np.round(emb / scale[:, None]), -127, 127).astype(np.int8)
        g.initializer.append(numpy_helper.from_array(q, EMB + "_q"))
        g.initializer.append(numpy_helper.from_array(scale.astype(np.float32), EMB + "_scale"))
        out = gather.output[0]
        gather.input[0] = EMB + "_q"
        gather.output[0] = out + "_q"
        idx = list(g.node).index(gather)
        # dequantize AFTER the gather (x = q * scale[row]) and gather the per-row scale with the same indices
        sc_out = out + "_scale_rows"
        g.node.insert(idx + 1, helper.make_node("Gather", [EMB + "_scale", gather.input[1]], [sc_out], axis=0, name="emb_scale_gather"))
        g.node.insert(idx + 2, helper.make_node("Cast", [out + "_q"], [out + "_qf"], to=TensorProto.FLOAT, name="emb_cast"))
        g.node.insert(idx + 3, helper.make_node("Unsqueeze", [sc_out, EMB + "_axes"], [sc_out + "_u"], name="emb_unsq"))
        g.initializer.append(numpy_helper.from_array(np.array([-1], dtype=np.int64), EMB + "_axes"))
        g.node.insert(idx + 4, helper.make_node("Mul", [out + "_qf", sc_out + "_u"], [out], name="emb_mul"))
    else:
        raise SystemExit("bad --embed")


if matmul_mode == "fp16":
    # store MatMul weights as fp16 and Cast back to fp32 (smaller file, same fp32 math)
    done = {}
    for n in list(g.node):
        if n.op_type != "MatMul":
            continue
        for k, name in enumerate(n.input):
            t = {i.name: i for i in g.initializer}.get(name)
            if t is None or t.data_type != TensorProto.FLOAT or len(t.dims) < 2:
                continue
            if name not in done:
                arr = numpy_helper.to_array(t)
                g.initializer.remove(t)
                g.initializer.append(numpy_helper.from_array(arr.astype(np.float16), name + "_fp16"))
                done[name] = name + "_f32cast"
                idx = list(g.node).index(n)
                g.node.insert(idx, helper.make_node("Cast", [name + "_fp16"], [done[name]], to=TensorProto.FLOAT, name=name + "_castnode"))
            n.input[k] = done[name]

tmp = tempfile.mkdtemp()
p1 = os.path.join(tmp, "stage1.onnx")
onnx.save(m, p1)
if matmul_mode == "int8":
    from onnxruntime.quantization import quantize_dynamic, QuantType
    quantize_dynamic(p1, dst, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
else:
    onnx.save(m, dst)
print(dst, os.path.getsize(dst) / 1e6, "MB")

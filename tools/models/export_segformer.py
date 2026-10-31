"""Exports SegFormer-B0 (ADE20K, nvidia/segformer-b0-finetuned-ade-512-512) to ONNX at 256x256 for the
walkable-ground channel, and checks the ONNX output against PyTorch on a test pattern.
Usage: python export_segformer.py <out.onnx>
The weights are under the NVIDIA Source Code License (research and evaluation use only)."""
import sys
import numpy as np
import torch
import onnxruntime as ort
from transformers import SegformerForSemanticSegmentation

MODEL = "nvidia/segformer-b0-finetuned-ade-512-512"
REVISION = "489d5cd81a0b59fab9b7ea758d3548ebe99677da"
SIZE = 256
out = sys.argv[1]
model = SegformerForSemanticSegmentation.from_pretrained(MODEL, revision=REVISION).eval()
x = torch.from_numpy(np.random.default_rng(0).standard_normal((1, 3, SIZE, SIZE)).astype(np.float32))
torch.onnx.export(model, x, out, input_names=["pixel_values"], output_names=["logits"], opset_version=17, dynamo=False)
with torch.no_grad():
    ref = model(pixel_values=x).logits.numpy()
got = ort.InferenceSession(out, providers=["CPUExecutionProvider"]).run(None, {"pixel_values": x.numpy()})[0]
agree = float((ref.argmax(1) == got.argmax(1)).mean())
print(f"exported {out}: logits {got.shape}, class agreement with PyTorch {agree:.4f}")
assert agree > 0.999, "ONNX export does not match PyTorch"

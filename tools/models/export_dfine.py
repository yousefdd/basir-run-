"""
Exports the official D-FINE-N COCO weights (Apache 2.0) to the ONNX file the app runs.

Usage: python export_dfine.py <D-FINE checkout> <dfine_n_coco.pth> <output.onnx>

The graph stops before D-FINE's own post-processor, so the app gets the raw DETR outputs it
already decodes: pixel_values [1,3,640,640] (RGB / 255) → logits [1,300,80] (apply sigmoid)
and pred_boxes [1,300,4] (cx, cy, w, h in 0..1 of the square input). Class index i is the
i-th COCO class in the usual 80-class order (person, bicycle, car, …).
The export is checked against the PyTorch model on random input before it is accepted.
"""
import sys

import numpy as np
import torch

repo, weights, out = sys.argv[1:4]
sys.path.insert(0, repo)
from src.core import YAMLConfig  # noqa: E402

cfg = YAMLConfig(f"{repo}/configs/dfine/dfine_hgnetv2_n_coco.yml", resume=weights)
cfg.yaml_cfg["HGNetv2"]["pretrained"] = False
checkpoint = torch.load(weights, map_location="cpu", weights_only=False)
state = checkpoint["ema"]["module"] if "ema" in checkpoint else checkpoint["model"]
cfg.model.load_state_dict(state)
model = cfg.model.deploy().eval()


class Raw(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, pixel_values):
        o = self.m(pixel_values)
        return o["pred_logits"], o["pred_boxes"]


raw = Raw(model).eval()
x = torch.rand(1, 3, 640, 640)
with torch.no_grad():
    ref_logits, ref_boxes = raw(x)
torch.onnx.export(
    raw, (x,), out,
    input_names=["pixel_values"], output_names=["logits", "pred_boxes"],
    opset_version=17, do_constant_folding=True, dynamo=False,
)

import onnx  # noqa: E402
import onnxruntime as ort  # noqa: E402

onnx.checker.check_model(onnx.load(out))
sess = ort.InferenceSession(out, providers=["CPUExecutionProvider"])
logits, boxes = sess.run(None, {"pixel_values": x.numpy()})
d_logits = float(np.abs(logits - ref_logits.numpy()).max())
d_boxes = float(np.abs(boxes - ref_boxes.numpy()).max())
print(f"shapes {logits.shape} {boxes.shape}; max diff logits {d_logits:.2e}, boxes {d_boxes:.2e}")
assert logits.shape == (1, 300, 80) and boxes.shape == (1, 300, 4), "unexpected output shapes"
assert d_logits < 1e-3 and d_boxes < 1e-4, "ONNX output differs from PyTorch"

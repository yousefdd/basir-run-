"""SegFormer-B2 off-road model from roboticslaburjc-org/cross-dataset-visual-offroad-segmentation (Apache-2.0,
trained on RELLIS-3D + GOOSE with the authors' coarse 8-class ontology, mmseg checkpoint). The weights are
read with safe_ckpt (no code from the file runs); the encoder is Hugging Face's Segformer encoder with
mmseg keys renamed, the decoder is a re-implementation of mmseg's SegformerHead (1x1 ConvModule per stage
with BN+ReLU, upsample, concat, fusion ConvModule, 1x1 classifier).
Usage (library): m = OffroadSegformer(path); logits = m(x)  # x: (1,3,H,W) ImageNet-normalised"""
import os, sys, torch, torch.nn as nn, torch.nn.functional as F
sys.path.insert(0, os.path.dirname(__file__)); import safe_ckpt
from transformers import SegformerConfig
from transformers import SegformerModel

def convert_encoder(sd):
    out = {}
    for k, v in sd.items():
        if not k.startswith("backbone."): continue
        p = k.split(".")[1:]                                     # layers.i.(0|1|2)...
        i, part = int(p[1]), p[2]
        if part == "0":                                          # patch embedding
            name = {"projection": "proj", "norm": "layer_norm"}[p[3]]
            out[f"stages.{i}.patch_embeddings.{name}.{p[4]}"] = v
        elif part == "2":
            out[f"stages.{i}.layer_norm.{p[3]}"] = v
        else:
            j, rest = int(p[3]), p[4:]
            b = f"stages.{i}.blocks.{j}."
            if rest[0] in ("norm1", "norm2"):
                out[b + {"norm1": "layernorm_before", "norm2": "layernorm_after"}[rest[0]] + "." + rest[1]] = v
            elif rest[0] == "attn":
                if rest[1] == "attn" and rest[2].startswith("in_proj"):
                    q, kk, vv = v.chunk(3, 0); suf = "weight" if rest[2] == "in_proj_weight" else "bias"
                    out[b + f"attention.q_proj.{suf}"] = q; out[b + f"attention.k_proj.{suf}"] = kk; out[b + f"attention.v_proj.{suf}"] = vv
                elif rest[1] == "attn" and rest[2] == "out_proj":
                    out[b + f"attention.o_proj.{rest[3]}"] = v
                elif rest[1] == "sr":
                    out[b + f"attention.sequence_reduction.sequence_reduction.{rest[2]}"] = v
                elif rest[1] == "norm":
                    out[b + f"attention.sequence_reduction.layer_norm.{rest[2]}"] = v
            elif rest[0] == "ffn":
                li, suf = rest[2], rest[3]
                name = {"0": "fc1", "1": "dwconv.dwconv", "4": "fc2"}[li]
                if name != "dwconv.dwconv" and suf == "weight": v = v[:, :, 0, 0]
                out[b + f"mlp.{name}.{suf}"] = v
    return out

class ConvBNReLU(nn.Module):
    def __init__(self, i, o):
        super().__init__(); self.conv = nn.Conv2d(i, o, 1, bias=False); self.bn = nn.BatchNorm2d(o)
    def forward(self, x): return F.relu(self.bn(self.conv(x)))

class OffroadSegformer(nn.Module):
    def __init__(self, path):
        super().__init__()
        sd, _ = safe_ckpt.load(path)
        nc, ch = sd["decode_head.conv_seg.weight"].shape[:2]
        cfg = SegformerConfig(num_channels=3, num_encoder_blocks=4, depths=[3, 4, 6, 3], sr_ratios=[8, 4, 2, 1], hidden_sizes=[64, 128, 320, 512],
                              patch_sizes=[7, 3, 3, 3], strides=[4, 2, 2, 2], num_attention_heads=[1, 2, 5, 8], mlp_ratios=[4, 4, 4, 4])
        self.encoder = SegformerModel(cfg)
        missing, unexpected = self.encoder.load_state_dict(convert_encoder(sd), strict=False)
        assert not missing and not unexpected, (missing[:5], unexpected[:5])
        self.convs = nn.ModuleList([ConvBNReLU(c, ch) for c in [64, 128, 320, 512]])
        self.fusion = ConvBNReLU(ch * 4, ch); self.cls = nn.Conv2d(ch, nc, 1)
        dh = {}
        for k, v in sd.items():
            if not k.startswith("decode_head."): continue
            r = k[len("decode_head."):]
            r = r.replace("convs.", "convs.").replace("fusion_conv.", "fusion.").replace("conv_seg.", "cls.")
            dh[r] = v
        missing, unexpected = self.load_state_dict({**{"encoder." + k: v for k, v in self.encoder.state_dict().items()}, **dh}, strict=False)
        assert not [m for m in missing if not m.startswith("encoder.")] and not unexpected, (missing[:5], unexpected[:5])
        self.num_classes = nc; self.eval()
    @torch.no_grad()
    def forward(self, x):
        feats, h = [], x
        for st in self.encoder.stages: h = st(h); feats.append(h)
        size = feats[0].shape[2:]
        ups = [F.interpolate(c(f), size=size, mode="bilinear", align_corners=False) for c, f in zip(self.convs, feats)]
        return self.cls(self.fusion(torch.cat(ups, 1)))

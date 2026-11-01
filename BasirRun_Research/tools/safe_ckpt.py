"""Reads tensors out of a whole-model PyTorch pickle (mmseg / mmcv / mmengine modules) WITHOUT importing or
running any class from the file: every global except torch's tensor rebuilders and plain containers becomes
an inert Stub that only stores its pickled state. The tensors are then collected by walking the stub tree
(_parameters / _buffers / _modules), giving an mmseg-style state dict, and the Config object (if any) is
returned as nested plain data. Usage (library): load(path) -> (state_dict, config_dict_or_None)"""
import collections, io, pickle, zipfile, torch

class Stub:
    def __init__(self, *a, **k): self._args = a
    def __setstate__(self, s):
        if isinstance(s, dict): self.__dict__.update(s)
        elif isinstance(s, tuple) and len(s) == 2 and isinstance(s[1], dict): self.__dict__.update(s[0] or {}); self.__dict__.update(s[1])
        else: self._state = s
    def __call__(self, *a, **k): return Stub()

ALLOWED = {("torch._utils", "_rebuild_tensor_v2"), ("torch._utils", "_rebuild_parameter"), ("collections", "OrderedDict"),
           ("builtins", "set"), ("__builtin__", "set"), ("torch", "FloatStorage"), ("torch", "LongStorage"), ("torch", "HalfStorage"),
           ("torch", "IntStorage"), ("torch", "BoolStorage"), ("torch", "DoubleStorage"), ("torch", "ByteStorage"), ("torch", "Size"),
           ("builtins", "slice"), ("torch", "device")}

def load(path):
    z = zipfile.ZipFile(path); pkl = [n for n in z.namelist() if n.endswith("data.pkl")][0]; root = pkl[:-len("data.pkl")]
    class U(pickle.Unpickler):
        def find_class(self, mod, name):
            if (mod, name) in ALLOWED:
                if name.endswith("Storage"): return getattr(torch, name)
                return {"OrderedDict": collections.OrderedDict, "set": set, "slice": slice}.get(name) or getattr(__import__(mod, fromlist=[name]), name)
            return type(name, (Stub,), {})
        def persistent_load(self, pid):                       # ('storage', storage_type, key, location, numel)
            _, st, key, _, numel = pid
            dtype = st.dtype if hasattr(st, "dtype") else st(0).dtype
            data = z.read(f"{root}data/{key}")
            un = torch.frombuffer(bytearray(data), dtype=torch.uint8).untyped_storage() if len(data) else torch.UntypedStorage(0)
            return torch.storage.TypedStorage(wrap_storage=un, dtype=dtype, _internal=True)
    obj = U(io.BytesIO(z.read(pkl))).load()
    sd = collections.OrderedDict()
    def walk(m, pre):
        if isinstance(m, dict): d = m
        else: d = getattr(m, "__dict__", {})
        for k, t in (d.get("_parameters") or {}).items():
            if t is not None: sd[pre + k] = t.detach() if hasattr(t, "detach") else t
        for k, t in (d.get("_buffers") or {}).items():
            if t is not None: sd[pre + k] = t
        for k, c in (d.get("_modules") or {}).items():
            if c is not None: walk(c, pre + k + ".")
    model = obj.get("model", obj) if isinstance(obj, dict) else obj
    if isinstance(model, dict) and "state_dict" in model: return model["state_dict"], None
    walk(model, "")
    cfg = None
    def plain(x, depth=0):
        if depth > 12: return None
        if isinstance(x, (str, int, float, bool)) or x is None: return x
        if isinstance(x, (list, tuple)): return [plain(v, depth + 1) for v in x]
        if isinstance(x, dict): return {str(k): plain(v, depth + 1) for k, v in x.items()}
        if isinstance(x, Stub): return plain({k: v for k, v in x.__dict__.items() if not k.startswith("_modules")}, depth + 1)
        return str(type(x))
    for k in ("cfg", "_cfg", "config"):
        c = getattr(model, k, None) if not isinstance(model, dict) else model.get(k)
        if c is not None: cfg = plain(c); break
    return sd, cfg

if __name__ == "__main__":
    import sys
    sd, cfg = load(sys.argv[1])
    print(len(sd), "tensors"); [print(k, tuple(v.shape)) for k, v in list(sd.items())[:6]]
    print("...", [(k, tuple(v.shape)) for k, v in sd.items() if "conv_seg" in k])

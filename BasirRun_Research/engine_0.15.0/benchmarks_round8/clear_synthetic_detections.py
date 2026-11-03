"""Synthetic scenes contain no detector-class objects: D-FINE's hallucinations on the renders ("stop sign", "umbrella"
on the painted lines and the horizon band) are removed (kept in detections.jsonl.raw). The barrier is left to depth."""
import os, sys
p = os.path.join(sys.argv[1], "detections.jsonl")
if not os.path.exists(p + ".raw"):
    n = sum(1 for _ in open(p)); open(p + ".raw", "w").write(open(p).read()); open(p, "w").write("[]\n" * n)

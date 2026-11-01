"""G4: >=10 datasets, every column filled, each licence cell says where it comes from or that it is unverified,
and the datasets we say we used exist on disk."""
import csv, os, sys
R = "/home/user/basir-run-/BasirRun_Research"
rows = list(csv.DictReader(open(R + "/research_notes/datasets_round4.csv")))
if len(rows) < 10: sys.exit("fewer than 10 datasets")
for r in rows:
    if not all(v.strip() for v in r.values()): sys.exit("empty field in " + r["dataset"])
    if not any(k in r["licence"].lower() for k in ("readme", "site", "page", "website", "verified", "repo", "paper", "licence page")): sys.exit("licence source not stated for " + r["dataset"])
for name, path in (("SANPO-Real", "datasets/sanpo/frames"), ("RUGD", "datasets/rugd/test/image_res")):
    if not os.path.isdir(os.path.join(R, path)) or not os.listdir(os.path.join(R, path)): sys.exit("missing on disk: " + name)
print(len(rows), "datasets"); print("DATASETS_OK")

"""G1: the round-4 audit exists, answers the 12 problems (table rows | 1 | .. | 12 |) with file references, and
was committed before any round-4 commit that touches engine/ or app/ source."""
import os, re, subprocess, sys
root = "/home/user/basir-run-"; rel = "BasirRun_Research/reports/BasirRun_Current_Engine_Audit.md"; p = os.path.join(root, rel)
if not os.path.exists(p): sys.exit("audit missing")
t = open(p).read()
rows = [n for n in range(1, 13) if re.search(rf"^\| {n} \| .+\|.+\|.+\|", t, re.M)]
if len(rows) != 12: sys.exit(f"problem rows found: {rows}")
refs = re.findall(r"`[\w/.\-]+\.(?:kt|py|txt|csv|onnx|kts|md|mp4)", t)
if len(refs) < 15: sys.exit(f"too few file references: {len(refs)}")
log = subprocess.run(["git", "-C", root, "log", "--reverse", "--format=%H", "cc93aa5..HEAD"], capture_output=True, text=True).stdout.split()
first_audit = next((i for i, h in enumerate(log) if rel in subprocess.run(["git", "-C", root, "show", "--name-only", "--format=", h], capture_output=True, text=True).stdout), None)
if first_audit is None: sys.exit("audit not committed")
for i, h in enumerate(log[:first_audit]):
    files = subprocess.run(["git", "-C", root, "show", "--name-only", "--format=", h], capture_output=True, text=True).stdout.split()
    if any(f.startswith(("engine/src/", "app/src/")) for f in files): sys.exit(f"engine/app changed before the audit in {h[:8]}")
print("AUDIT_OK", len(refs), "file references")

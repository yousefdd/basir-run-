"""G2: >=40 distinct repos in survey_round4.csv; each one's clone exists, its last commit date equals the table,
its README confirmed the role in the probe, and the areas A-F are each covered by >=2 repos."""
import csv, os, subprocess, sys, collections
R = "/home/user/basir-run-/BasirRun_Research"
rows = list(csv.DictReader(open(R + "/research_notes/survey_round4.csv")))
probe = {r["repo"]: r for r in csv.DictReader(open(R + "/github_repositories/survey_probe.csv"))}
seen = set(); areas = collections.Counter()
for r in rows:
    d = f"{R}/github_repositories/_probe/{r['repo'].replace('/', '__')}"
    if not os.path.isdir(d): sys.exit(f"no clone {r['repo']}")
    date = subprocess.run(["git", "-C", d, "log", "-1", "--format=%cs"], capture_output=True, text=True).stdout.strip()
    if date != r["last_commit"]: sys.exit(f"{r['repo']}: {r['last_commit']} vs {date}")
    if probe[r["repo"]]["readme_confirms_role"] != "True": sys.exit(f"README does not confirm {r['repo']}")
    if not (r["decision"] and r["architecture"] and r["license"]): sys.exit(f"incomplete {r['repo']}")
    seen.add(r["repo"]); [areas.update([a.strip()]) for a in r["area"].split(",")]
missing = [a for a in "ABCDEF" if areas[a] < 2]
if len(seen) < 40 or len(seen) != len(rows) or missing: sys.exit(f"repos {len(seen)}, missing areas {missing}")
print(f"{len(seen)} repos verified; areas {dict(sorted(areas.items()))}"); print("SURVEY40_OK")

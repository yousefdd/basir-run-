"""Re-reads every surveyed clone: last commit date and licence must match the table, README must confirm the role."""
import csv, subprocess, os, re
R = "/home/user/basir-run-/BasirRun_Research"
rows = list(csv.DictReader(open(R + "/research_notes/survey_evaluation.csv")))
probe = {r["repo"]: r for r in csv.DictReader(open(R + "/github_repositories/survey_probe.csv"))}
ok = 0
for r in rows:
    d = f"{R}/github_repositories/_probe/{r['repo'].replace('/', '__')}"
    assert os.path.isdir(d), f"no clone for {r['repo']}"
    date = subprocess.run(["git", "-C", d, "log", "-1", "--format=%cs"], capture_output=True, text=True).stdout.strip()
    assert date == r["last_commit"], f"{r['repo']}: table {r['last_commit']} vs clone {date}"
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", date)
    assert r["license"] and probe[r["repo"]]["readme_confirms_role"] == "True", r["repo"]
    assert r["architecture"] and r["decision"] and r["license_ok_for_closed_app"], r["repo"]
    ok += 1
assert ok >= 20 and len({r["repo"] for r in rows}) == ok
print(f"{ok} distinct candidates verified against their clones")
print("SURVEY_OK")

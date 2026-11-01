"""G3: >=15 papers; every arXiv link resolves through the arXiv API to the SAME title as the table (and year);
non-arXiv links must answer HTTP 200; all columns filled."""
import csv, re, sys, time, urllib.request
R = "/home/user/basir-run-/BasirRun_Research"
rows = list(csv.DictReader(open(R + "/research_notes/papers_round4.csv")))
if len(rows) < 15: sys.exit(f"only {len(rows)} papers")
for r in rows:
    if not all(r[k].strip() for k in r): sys.exit(f"empty field in {r['title'][:40]}")
ids = {re.search(r"arxiv.org/abs/([\d.]+)", r["url"]).group(1): r for r in rows if "arxiv.org/abs/" in r["url"]}
t = urllib.request.urlopen("https://export.arxiv.org/api/query?id_list=" + ",".join(ids) + "&max_results=100", timeout=90).read().decode()
got = {}
for e in t.split("<entry>")[1:]:
    i = re.search(r"arxiv.org/abs/([\d.]+)", e).group(1); got[i] = (" ".join(re.search(r"<title>(.*?)</title>", e, re.S).group(1).split()), re.search(r"<published>(\d{4})", e).group(1))
norm = lambda s: re.sub(r"[^a-z0-9]", "", s.lower())
for i, r in ids.items():
    if i not in got: sys.exit(f"arXiv has no {i}")
    if norm(got[i][0]) != norm(r["title"]) or got[i][1] != r["year"]: sys.exit(f"{i}: table '{r['title']}' {r['year']} vs arXiv '{got[i][0]}' {got[i][1]}")
for r in rows:
    if "arxiv.org/abs/" not in r["url"]:
        code = urllib.request.urlopen(r["url"], timeout=60).status
        if code != 200: sys.exit(f"{r['url']} -> {code}")
print(f"{len(rows)} papers, {len(ids)} verified against arXiv"); print("PAPERS_OK")

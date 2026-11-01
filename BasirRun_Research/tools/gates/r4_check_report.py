"""G12: the final report has the 17 requested sections in order; every number in the section-9 tables occurs in
the labelled result files; the section-10 block equals benchmarks/results/video_benchmark.txt; the section-11
block equals integration_decision.txt; the report does not claim readiness for independent running."""
import re, sys
R = "/home/user/basir-run-/BasirRun_Research"
t = open(R + "/reports/BasirRun_Deep_Research_Final_Report.md").read()
heads = re.findall(r"^## (\d+)\. ", t, re.M)
if heads != [str(i) for i in range(1, 18)]: sys.exit(f"sections {heads}")
sec = lambda n: t.split(f"## {n}. ")[1].split("\n## ")[0]
src = open(R + "/benchmarks/results/terrain_labelled.csv").read() + open(R + "/benchmarks/results/terrain_obstacles.csv").read()
nums = [c.strip() for l in sec(9).splitlines() if l.startswith("|") and not l.startswith("|---") for c in l.strip("|").split("|") if re.fullmatch(r"\s*-?\d+(\.\d+)?\s*", c)]
bad = [n for n in nums if n not in src]
if not nums or bad: sys.exit(f"section 9 numbers not in result files: {bad[:5]} (checked {len(nums)})")
block = lambda s: s.split("```\n")[1].split("\n```")[0].strip()
if block(sec(10)) != open(R + "/benchmarks/results/video_benchmark.txt").read().strip(): sys.exit("section 10 differs from video_benchmark.txt")
if block(sec(11)) != open(R + "/benchmarks/results/integration_decision.txt").read().strip(): sys.exit("section 11 differs from integration_decision.txt")
if re.search(r"ready for independent running(?! )", t) and "not** declared ready" not in t: sys.exit("readiness claim")
print(f"17 sections; {len(nums)} section-9 numbers traced"); print("REPORT_OK")

"""Aggregates per_frame.csv into IoU / precision / recall / corridor false-walkable rate, per method and category."""
import csv, json, sys, collections, statistics
rows = list(csv.DictReader(open(sys.argv[1])))
def agg(rs, region):
    tp = fp = fn = neg = 0
    for r in rs:
        d = json.loads(r[region]); tp += d["tp"]; fp += d["fp"]; fn += d["fn"]; neg += d["neg"]
    iou = tp / max(1, tp + fp + fn); prec = tp / max(1, tp + fp); rec = tp / max(1, tp + fn)
    return iou, prec, rec, fp / max(1, neg)
def frame_iou(r):
    d = json.loads(r["corridor"]); return d["tp"] / max(1, d["tp"] + d["fp"] + d["fn"])
methods = list(dict.fromkeys(r["method"] for r in rows))
print("method | IoU all | prec | recall | corridor IoU | corridor false-walkable | frames corridor IoU<0.5 | ms median")
for m in methods:
    rs = [r for r in rows if r["method"] == m]
    a = agg(rs, "all"); c = agg(rs, "corridor")
    bad = sum(frame_iou(r) < 0.5 for r in rs)
    ms = [float(r["ms"]) for r in rs if r["ms"]]
    print(f"{m} | {a[0]:.3f} | {a[1]:.3f} | {a[2]:.3f} | {c[0]:.3f} | {c[3]:.3f} | {bad}/{len(rs)} | {statistics.median(ms) if ms else '-'}")
print("\ncorridor IoU by category")
cats = sorted(set(r["category"] for r in rows))
print("method | " + " | ".join(cats))
for m in methods:
    print(m + " | " + " | ".join(f"{agg([r for r in rows if r['method']==m and r['category']==c], 'corridor')[0]:.2f}" for c in cats))

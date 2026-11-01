"""G6: the folder layout asked for in the round-4 brief exists under BasirRun_Research, and the manifest has every
requested column."""
import csv, os, sys
R = "/home/user/basir-run-/BasirRun_Research"
need = ["github_repositories", "research_papers", "pretrained_models", "datasets/terrain", "datasets/walkable_area", "datasets/path_detection", "datasets/obstacle_detection",
        "datasets/egocentric", "videos/walking", "videos/running", "videos/athletics_tracks", "videos/park_paths", "videos/forest_trails", "videos/gravel_paths",
        "videos/rocky_surfaces", "videos/wet_surfaces", "videos/difficult_lighting", "videos/low_contrast", "videos/obstacles", "annotations", "benchmarks", "experiments",
        "visual_comparisons", "reports", "manifests"]
miss = [d for d in need if not os.path.isdir(os.path.join(R, d))]
if miss: sys.exit(f"missing dirs {miss}")
cols = next(csv.reader(open(R + "/manifests/video_dataset_manifest.csv")))
want = ["video_id", "source_url", "download_source", "licence", "motion", "camera", "environment", "ground", "lighting", "painted_lines", "curves", "people", "obstacles",
        "duration_s", "resolution", "fps", "download_status", "inspection_status", "annotation_status"]
if [c for c in want if c not in cols]: sys.exit(f"missing columns {[c for c in want if c not in cols]}")
print("LAYOUT_OK")

# Round 8 benchmark harness

- `clips_sanpo.txt` — the 16 SANPO-Real windows (name, session id, first frame), 300 frames each, CC BY 4.0.
- `fetch_sanpo.py` → `bench_models.py` (the four ONNX models per frame, as the app feeds them) → clip folders.
- `render_track.py` / `render_all.sh` — the 12 synthetic sports-ground clips with exact ground truth (`truth.json`).
- `make_ego.py` — gyroscope stand-in (`ego.json`) from SANPO poses / synthetic camera yaw.
- `run_compare.sh` — replays each clip with the 0.14.0 rules (`base`) and round 8 (`new`), then `metrics.py`.
- `compare.py`, `build_report.py` — the tables of `docs/BasirRun_Round8_Report.md`; `ablate.sh` — one-setting ablations.
- `results/` — every per-clip metric file and the summaries used in the report.

The scripts expect the layout `bench/{tools,clips,raw,out}` next to the engine folder, as used for this round.

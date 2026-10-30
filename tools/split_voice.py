"""
Cuts the long voice recordings (one per part of docs/voice/VOICE_PARAGRAPHS.md) into one file
per phrase, named by the phrase id the app looks for: app/src/main/assets/voice/<id>.mp3.

Usage:
    python3 tools/split_voice.py part1.mp3 part2.mp3 part3.mp3

How it finds the cuts: the paragraphs put a <break time="1.5s" /> between phrases, which is
longer than any pause inside a phrase (commas and full stops give a few tenths of a second).
ffmpeg lists every silence of at least MIN_SILENCE seconds; inside each part, the (n - 1)
longest silences between speech are the phrase boundaries, where n is the number of phrases in
that part. The cut is accepted only when the shortest chosen gap is clearly longer than the
longest gap left inside a phrase; otherwise nothing is written and the gaps are reported, so a
wrong cut never ends up under a wrong name.

Needs ffmpeg with libmp3lame.
"""
import csv
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TABLE = os.path.join(ROOT, "docs", "voice", "phrases_tashkeel.tsv")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "voice")
MIN_SILENCE = 0.25   # seconds
NOISE_DB = -40       # what counts as silence
MARGIN = 1.3         # chosen gaps must be this much longer than the longest gap kept inside a phrase
MIN_BREAK = 0.9      # and at least this long (the paragraphs ask for 1.5 s)
PAD_BEFORE = 0.06    # seconds of silence kept before and after each phrase
PAD_AFTER = 0.15


def phrases_by_part():
    parts = {}
    with open(TABLE, encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            parts.setdefault(int(row["part"]), []).append(row["id"])
    return [parts[k] for k in sorted(parts)]


def duration(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         capture_output=True, text=True, check=True).stdout
    return float(out.strip())


def silences(path, noise_db=NOISE_DB, min_silence=MIN_SILENCE):
    """(start, end) of every silence, from ffmpeg's silencedetect."""
    log = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", path,
                          "-af", f"silencedetect=noise={noise_db}dB:d={min_silence}", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    starts = [float(x) for x in re.findall(r"silence_start: (-?[\d.]+)", log)]
    ends = [float(x) for x in re.findall(r"silence_end: ([\d.]+)", log)]
    total = duration(path)
    if len(ends) < len(starts):  # silence running to the end of the file
        ends.append(total)
    return [(max(0.0, s), e) for s, e in zip(starts, ends)], total


def cut_points(path, n):
    """Speech spans (start, end) of the n phrases in one recording, or raise with a report."""
    sil, total = silences(path)
    # Silence at the very start or end of the file is not a gap between phrases (the encoder
    # may leave a few hundredths of a second of padding after it).
    head = sil[0] if sil and sil[0][0] < 0.1 else None
    tail = sil[-1] if sil and total - sil[-1][1] < MIN_SILENCE and sil[-1] is not head else None
    inner = [g for g in sil if g is not head and g is not tail]
    if len(inner) < n - 1:
        raise ValueError(f"{os.path.basename(path)}: found {len(inner) + 1} spoken pieces, expected {n}. "
                         "Was every phrase read, with the pauses between them?")
    ranked = sorted(inner, key=lambda g: g[1] - g[0], reverse=True)
    chosen = sorted(ranked[:n - 1])
    shortest_chosen = min((e - s for s, e in chosen), default=float("inf"))
    longest_left = max((e - s for s, e in ranked[n - 1:]), default=0.0)
    if shortest_chosen < MIN_BREAK or (longest_left > 0 and shortest_chosen < MARGIN * longest_left):
        raise ValueError(f"{os.path.basename(path)}: the pauses between phrases ({shortest_chosen:.2f}s) are not clearly "
                         f"longer than the pauses inside them ({longest_left:.2f}s); not cutting.")
    bounds = [head[1] if head else 0.0] + [x for gap in chosen for x in gap] + [tail[0] if tail else total]
    return [(bounds[2 * i], bounds[2 * i + 1]) for i in range(n)], shortest_chosen, longest_left


def export(src, start, end, dst):
    start = max(0.0, start - PAD_BEFORE)
    length = end + PAD_AFTER - start
    fade = min(0.02, length / 4)
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", f"{start:.3f}", "-t", f"{length:.3f}",
                    "-i", src, "-af", f"afade=t=in:d={fade:.3f},areverse,afade=t=in:d={fade:.3f},areverse",
                    "-ac", "1", "-ar", "44100", "-codec:a", "libmp3lame", "-b:a", "96k",
                    # No tags of any kind in the app's files (source, tool or generator names).
                    "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", "-id3v2_version", "0",
                    "-write_xing", "0", dst], check=True)


def main(files):
    parts = phrases_by_part()
    if len(files) != len(parts):
        sys.exit(f"Give {len(parts)} recordings, one per part, in order (got {len(files)}).")
    plans = []
    for path, ids in zip(files, parts):
        spans, gap, inside = cut_points(path, len(ids))
        print(f"{os.path.basename(path)}: {len(ids)} phrases; pauses between ≥ {gap:.2f}s, inside ≤ {inside:.2f}s")
        plans.append((path, ids, spans))
    os.makedirs(OUT, exist_ok=True)
    for path, ids, spans in plans:
        for pid, (s, e) in zip(ids, spans):
            export(path, s, e, os.path.join(OUT, pid + ".mp3"))
            print(f"  {pid}.mp3  {e - s:5.2f}s")
    print(f"Wrote {sum(len(p[1]) for p in plans)} files to {OUT}")


if __name__ == "__main__":
    main(sys.argv[1:])

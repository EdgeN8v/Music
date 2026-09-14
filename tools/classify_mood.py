#!/usr/bin/env python3
"""
Analyzes a folder of audio files and tags each one's ID3-style "genre" field
as "Energetic" or "Calm", as a *classification aid* — the app itself no
longer reads this tag directly (see SongRepository.applyMoodLabels). Run
this, then run export_mood_labels.py against the same folder to turn these
genre tags into the label-file JSON the app actually imports (Settings →
激情/平静标记 → 从文件导入). This two-step split exists so a later tag
edit/re-rip never silently overwrites a mood you already reviewed by hand.

Two passes, because decoding audio is the slow part and you'll want to tune
the thresholds a few times without re-analyzing everything:

    python classify_mood.py scan   /path/to/music --cache features.csv
    python classify_mood.py apply  features.csv --dry-run
    python classify_mood.py apply  features.csv

`scan` walks the folder recursively and extracts four features per track
with librosa:
  - tempo (BPM)              - fast songs read as more energetic
  - RMS energy (loudness)    - louder mixes read as more energetic
  - spectral centroid        - brighter/harsher timbre reads as more energetic
  - onset rate (notes/sec)   - busier rhythms read as more energetic
Results go into a CSV cache so re-running `apply` with different thresholds
doesn't require re-decoding every file.

`apply` reads that CSV, z-scores and sums the four features into one energy
score per track, ranks all tracks by it, and tags the top --energetic-pct
percent "Energetic" and the bottom --calm-pct percent "Calm" — the middle is
left alone (not every song has to be one or the other). Already-tagged files
are skipped unless --force is passed, so re-running after adding new songs
won't reshuffle ones you already have tagged. --dry-run prints what would
happen without writing anything.

This is a starting point, not a finished classifier — thresholds are simple
percentile cuts on a linear combination of four features, not a trained
model. Expect to eyeball a few "Energetic"/"Calm" picks per run and adjust
--energetic-pct/--calm-pct (or the feature weights below) until it matches
your taste.

Requires: pip install librosa mutagen numpy
"""
import argparse
import csv
import sys
from pathlib import Path

AUDIO_EXTENSIONS = {".mp3", ".flac", ".m4a", ".ogg", ".wav"}


def iter_audio_files(root: Path):
    for path in root.rglob("*"):
        if path.suffix.lower() in AUDIO_EXTENSIONS:
            yield path


def extract_features(path: Path, offset: float = 15.0, duration: float = 60.0) -> dict:
    import librosa
    import numpy as np

    # A 60s slice starting past the intro is plenty for tempo/energy/timbre
    # stats and decodes ~3-5x faster than the whole track — matters once
    # you're running this over a thousand-song library. Falls back to
    # decoding from the start if the track is shorter than `offset`.
    try:
        y, sr = librosa.load(path, sr=22050, mono=True, offset=offset, duration=duration)
        if y.size == 0:
            raise ValueError("empty slice")
    except Exception:
        y, sr = librosa.load(path, sr=22050, mono=True, duration=duration)
    if y.size == 0:
        raise ValueError("empty audio")

    tempo, _ = librosa.beat.beat_track(y=y, sr=sr)
    rms = float(np.mean(librosa.feature.rms(y=y)))
    centroid = float(np.mean(librosa.feature.spectral_centroid(y=y, sr=sr)))
    onset_env = librosa.onset.onset_strength(y=y, sr=sr)
    onsets = librosa.onset.onset_detect(onset_envelope=onset_env, sr=sr)
    duration = librosa.get_duration(y=y, sr=sr)
    onset_rate = len(onsets) / duration if duration > 0 else 0.0

    return {
        "tempo": float(tempo),
        "rms": rms,
        "centroid": centroid,
        "onset_rate": onset_rate,
    }


def cmd_scan(args):
    root = Path(args.folder).expanduser()
    files = list(iter_audio_files(root))
    print(f"found {len(files)} audio files under {root}")

    cache_path = Path(args.cache)
    existing = {}
    if cache_path.exists() and not args.force_rescan:
        with cache_path.open(newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                existing[row["path"]] = row

    rows = []
    for i, path in enumerate(files, 1):
        key = str(path)
        if key in existing:
            rows.append(existing[key])
            continue
        try:
            feats = extract_features(path)
        except Exception as e:
            print(f"  [skip] {path.name}: {e}", file=sys.stderr)
            continue
        row = {"path": key, **{k: f"{v:.4f}" for k, v in feats.items()}}
        rows.append(row)
        print(f"  [{i}/{len(files)}] {path.name}  tempo={feats['tempo']:.0f} rms={feats['rms']:.3f}")

    with cache_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["path", "tempo", "rms", "centroid", "onset_rate"])
        writer.writeheader()
        writer.writerows(rows)
    print(f"wrote {len(rows)} rows to {cache_path}")


def zscore(values):
    import numpy as np
    arr = np.array(values, dtype=float)
    std = arr.std()
    if std == 0:
        return np.zeros_like(arr)
    return (arr - arr.mean()) / std


def cmd_apply(args):
    import numpy as np
    from mutagen import File as MutagenFile

    cache_path = Path(args.cache)
    with cache_path.open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        print("cache is empty — run 'scan' first")
        return

    tempo = [float(r["tempo"]) for r in rows]
    rms = [float(r["rms"]) for r in rows]
    centroid = [float(r["centroid"]) for r in rows]
    onset_rate = [float(r["onset_rate"]) for r in rows]

    # Equal-weighted sum of four z-scored features. Tweak the weights here if
    # e.g. tempo alone is overriding everything for your library.
    score = zscore(tempo) + zscore(rms) + zscore(centroid) + zscore(onset_rate)

    order = np.argsort(score)  # ascending: calmest first
    n = len(rows)
    calm_cutoff = int(n * args.calm_pct / 100)
    energetic_cutoff = int(n * (100 - args.energetic_pct) / 100)

    labels = [None] * n
    for rank, idx in enumerate(order):
        if rank < calm_cutoff:
            labels[idx] = "Calm"
        elif rank >= energetic_cutoff:
            labels[idx] = "Energetic"

    # Keys EasyID3/EasyMP4/FLAC's easy dict view exposes for plain text
    # frames — cover art (APIC) and other binary blocks live outside this
    # key space entirely, so clearing these never touches embedded artwork.
    # Title is dropped too (not just artist/album) so every file ends up
    # consistent — some tracks had an embedded title and others didn't,
    # which is more jarring than just always falling back to the filename.
    KEEP_KEYS = {"genre"} if not args.keep_title else {"title", "genre"}

    tagged = skipped = failed = untouched = 0
    for row, label in zip(rows, labels):
        path = Path(row["path"])
        if args.exclude and args.exclude in str(path):
            continue

        if label is None and not args.strip_tags:
            untouched += 1
            continue

        try:
            audio = MutagenFile(path, easy=True)
            if audio is None:
                raise ValueError("unsupported/unrecognized audio format")
            if audio.tags is None:
                audio.add_tags()

            if args.strip_tags:
                # Wipe every other field (artist/album/comment/track/etc — whatever
                # Kugou or the original rip stuffed in there), keep title, and set
                # genre only for songs that got an Energetic/Calm verdict.
                removed = [k for k in list(audio.tags.keys()) if k.lower() not in KEEP_KEYS]
                for key in removed:
                    del audio.tags[key]
                if label:
                    audio["genre"] = label
                elif "genre" in audio:
                    del audio.tags["genre"]

                if args.dry_run:
                    print(f"  [dry-run] {label or '-':<10} {path.name}  (stripped {len(removed)} fields)")
                else:
                    audio.save()
                tagged += 1
                if label is None:
                    untouched += 1
                continue

            if label is None:
                untouched += 1
                continue

            current = (audio.get("genre") or [None])[0]
            if current and not args.force:
                skipped += 1
                continue

            if args.dry_run:
                print(f"  [dry-run] {label:<10} {path.name}")
            else:
                audio["genre"] = label
                audio.save()
            tagged += 1
        except Exception as e:
            print(f"  [fail] {path.name}: {e}", file=sys.stderr)
            failed += 1

    verb = "would tag" if args.dry_run else "tagged"
    print(f"\n{verb} {tagged}, skipped {skipped} (already had genre), "
          f"failed {failed}, left {untouched} without an Energetic/Calm genre "
          f"(middle of the score range)")
    if not args.dry_run and tagged:
        print("Now trigger a library rescan in Navidrome so the app sees the new tags.")


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_scan = sub.add_parser("scan", help="extract audio features into a CSV cache")
    p_scan.add_argument("folder", help="music library root to scan recursively")
    p_scan.add_argument("--cache", default="features.csv", help="CSV file to write/update (default: features.csv)")
    p_scan.add_argument("--force-rescan", action="store_true", help="re-analyze files even if already in the cache")
    p_scan.set_defaults(func=cmd_scan)

    p_apply = sub.add_parser("apply", help="classify from a CSV cache and write genre tags")
    p_apply.add_argument("cache", help="CSV file produced by 'scan'")
    p_apply.add_argument("--energetic-pct", type=float, default=35,
                          help="top N%% by energy score -> Energetic (default: 35)")
    p_apply.add_argument("--calm-pct", type=float, default=35,
                          help="bottom N%% by energy score -> Calm (default: 35)")
    p_apply.add_argument("--force", action="store_true",
                          help="overwrite existing genre tags instead of skipping them")
    p_apply.add_argument("--dry-run", action="store_true",
                          help="print what would be tagged without writing files")
    p_apply.add_argument("--strip-tags", action="store_true",
                          help="also clear every other text field (artist/album/title/comment/etc), keeping only genre")
    p_apply.add_argument("--keep-title", action="store_true",
                          help="with --strip-tags, keep the existing title field instead of dropping it too")
    p_apply.add_argument("--exclude", default=None,
                          help="skip any cached path containing this substring")
    p_apply.set_defaults(func=cmd_apply)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

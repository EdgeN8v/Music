#!/usr/bin/env python3
"""
Builds the mood-label JSON file the app imports (Settings → 激情/平静标记 →
从文件导入) from each file's CURRENT title/artist/genre tags — meant to run
right after classify_mood.py has tagged genre="Energetic"/"Calm" on the files
you want labeled, before you strip those genre tags back out (the app no
longer reads them; see SongRepository.applyMoodLabels).

Key format has to match the app exactly: title + artist concatenated with no
separator (SongRepository.moodKey) — not a path, not a Navidrome song id, so
this needs no network access to your Subsonic server at all. The tradeoff:
if a song is later genuinely replaced (retitled, or different audio dropped
in under an unchanged file), its key won't match anymore and it silently
reverts to "no label" instead of inheriting a stale one — that's intentional
(see SettingsRepository.moodLabels), not a bug.

Usage:
    python export_mood_labels.py /path/to/music --out mood_labels.json

Then in the app: Settings → 激情/平静标记 → 从文件导入 → pick that file.

Requires: pip install mutagen
"""
import argparse
import json
import sys
from pathlib import Path

AUDIO_EXTENSIONS = {".mp3", ".flac", ".m4a", ".ogg", ".wav"}
VALID_MOODS = {"Energetic", "Calm"}


def iter_audio_files(root: Path):
    for path in sorted(root.rglob("*")):
        if path.suffix.lower() in AUDIO_EXTENSIONS:
            yield path


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", help="music library root to scan recursively")
    parser.add_argument("--out", default="mood_labels.json", help="JSON file to write (default: mood_labels.json)")
    args = parser.parse_args()

    from mutagen import File as MutagenFile

    root = Path(args.folder).expanduser()
    files = list(iter_audio_files(root))
    print(f"found {len(files)} audio files under {root}")

    labels = {}
    skipped_no_tags = skipped_no_mood = collisions = 0
    for path in files:
        audio = MutagenFile(path, easy=True)
        if not audio or not audio.tags:
            skipped_no_tags += 1
            continue
        genre = (audio.get("genre") or [None])[0]
        if genre not in VALID_MOODS:
            skipped_no_mood += 1
            continue
        title = (audio.get("title") or [""])[0]
        artist = (audio.get("artist") or [""])[0]
        if not title:
            print(f"  [skip] {path.name}: has genre={genre!r} but no title tag, can't build a key", file=sys.stderr)
            continue
        key = f"{title}{artist}"
        if key in labels and labels[key] != genre:
            print(f"  [collision] {path.name}: key already claimed by another song with a different mood", file=sys.stderr)
            collisions += 1
            continue
        labels[key] = genre

    out_path = Path(args.out)
    out_path.write_text(json.dumps(labels, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nwrote {len(labels)} label(s) to {out_path}")
    print(f"skipped {skipped_no_tags} with no tags at all, {skipped_no_mood} with no Energetic/Calm genre, {collisions} key collision(s)")
    print("Import this file in the app: Settings → 激情/平静标记 → 从文件导入")


if __name__ == "__main__":
    main()

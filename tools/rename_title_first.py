#!/usr/bin/env python3
"""
Renames files to "{title} - {artist}{ext}" (or just "{title}{ext}" if there's
no artist), reading Title/Artist straight off each file's own tags.

Meant to run AFTER fix_title_artist_tags.py has already corrected those tags
— this just mirrors that already-verified data into the filename instead of
re-deriving anything from the old name, so it inherits the same accuracy.

Why rename at all: the app's own Library already sorts by the Title tag
regardless of filename, but anything that browses the raw files outside the
app (a NAS file browser, another player pointed at the same folder) sorts by
filename — putting the song name first there makes both agree.

Handles:
  - filesystem-illegal characters (\\/:*?"<>|) — replaced with a safe dash
  - name collisions (case-insensitive, since most NAS shares are) — the
    second file to land on a given name gets " (2)", " (3)", etc.
  - no title tag at all — falls back to the existing filename, unchanged

Usage:
    python rename_title_first.py /path/to/music --dry-run
    python rename_title_first.py /path/to/music

Requires: pip install mutagen
"""
import argparse
import re
import sys
from pathlib import Path

AUDIO_EXTENSIONS = {".mp3", ".flac", ".m4a", ".ogg", ".wav"}
FORBIDDEN_CHARS = re.compile(r'[\\/:*?"<>|]')


def sanitize(name: str) -> str:
    return FORBIDDEN_CHARS.sub("-", name).strip()


def iter_audio_files(root: Path):
    for path in sorted(root.rglob("*")):
        if path.suffix.lower() in AUDIO_EXTENSIONS:
            yield path


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", help="music library root to scan recursively")
    parser.add_argument("--dry-run", action="store_true", help="print what would be renamed without touching any file")
    args = parser.parse_args()

    from mutagen import File as MutagenFile

    root = Path(args.folder).expanduser()
    files = list(iter_audio_files(root))
    print(f"found {len(files)} audio files under {root}")

    # Tracks names already claimed *this run*, per directory, case-insensitively.
    claimed: dict[Path, set[str]] = {}
    renamed = skipped = failed = 0

    for i, path in enumerate(files, 1):
        try:
            audio = MutagenFile(path, easy=True)
            title = (audio.get("title") or [None])[0] if audio and audio.tags else None
            artist = (audio.get("artist") or [""])[0] if audio and audio.tags else ""
        except Exception as e:
            print(f"  [fail] {path.name}: {e}", file=sys.stderr)
            failed += 1
            continue

        if not title:
            skipped += 1
            continue  # nothing to reorder around — leave the filename as-is

        base = sanitize(f"{title} - {artist}" if artist else title)
        directory_claims = claimed.setdefault(path.parent, {p.name.lower() for p in path.parent.iterdir()})

        candidate = base
        n = 2
        while True:
            new_name = f"{candidate}{path.suffix}"
            key = new_name.lower()
            if key == path.name.lower() or key not in directory_claims:
                break
            candidate = f"{base} ({n})"
            n += 1

        if new_name == path.name:
            skipped += 1
            continue

        directory_claims.discard(path.name.lower())
        directory_claims.add(new_name.lower())

        if args.dry_run:
            print(f"  [{i}/{len(files)}] {path.name}  ->  {new_name}")
        else:
            try:
                path.rename(path.with_name(new_name))
            except Exception as e:
                print(f"  [fail] {path.name}: {e}", file=sys.stderr)
                failed += 1
                continue
        renamed += 1

    verb = "would rename" if args.dry_run else "renamed"
    print(f"\n{verb} {renamed}, skipped {skipped} (no title tag or already correctly named), failed {failed}")
    if not args.dry_run and renamed:
        print("Now trigger a library rescan in Navidrome — Subsonic IDs are path-based, so a rename changes a song's id.")


if __name__ == "__main__":
    main()

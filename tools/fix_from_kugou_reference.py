#!/usr/bin/env python3
"""
Uses the original Kugou download folder as ground truth to fix any
remaining reversed title/artist tags in the working library — no guessing
needed where a match exists, since Kugou's own filenames ("艺人 - 歌名.kgg")
are presumably right for anything Kugou itself named.

For every Kugou file, split its name on the FIRST " - " into
(kugou_artist, kugou_title) — Kugou's convention is consistently
artist-first. For every working-library file, take its CURRENT (title,
artist) tag pair and look up the *unordered* pair {title, artist} (after
stripping whitespace and casing) against the Kugou set. If found:
  - if the current title already matches Kugou's title role: leave it alone
  - otherwise: swap — but always keep the WORKING file's own exact text
    (with whatever parenthetical qualifiers it has), never overwrite with
    Kugou's text. Kugou is only consulted to answer "which one is the title
    and which is the artist", not to replace the actual strings — so a
    remix/live variant here that isn't in Kugou verbatim still gets fixed as
    long as its (title, artist) pair matches some Kugou entry's pair.
Files with no match in Kugou are left untouched (nothing to compare against).

Usage:
    python fix_from_kugou_reference.py --kugou D:\\KugouMusic --library D:\\MP3 --dry-run
    python fix_from_kugou_reference.py --kugou D:\\KugouMusic --library D:\\MP3

Requires: pip install mutagen
"""
import argparse
import re
import sys
from pathlib import Path
from mutagen import File as MutagenFile

AUDIO_EXTENSIONS = {".mp3", ".flac", ".m4a", ".ogg", ".wav"}
KUGOU_EXTENSIONS = {".kgg", ".kgm", ".mp3", ".flac"}
FORBIDDEN_CHARS = re.compile(r'[\\/:*?"<>|]')


def normalize(s: str) -> str:
    return re.sub(r"\s+", "", s).strip().lower()


def sanitize(name: str) -> str:
    return FORBIDDEN_CHARS.sub("-", name).strip()


def build_kugou_lookup(kugou_dir: Path) -> dict:
    """pairkey (frozenset of 2 normalized strings) -> normalized title string."""
    lookup = {}
    skipped = 0
    for p in kugou_dir.iterdir():
        if not p.is_file() or p.suffix.lower() not in KUGOU_EXTENSIONS:
            continue
        stem = p.stem
        if " - " not in stem:
            skipped += 1
            continue
        artist, title = stem.split(" - ", 1)
        artist, title = artist.strip(), title.strip()
        na, nt = normalize(artist), normalize(title)
        if not na or not nt or na == nt:
            skipped += 1
            continue
        key = frozenset((na, nt))
        if key in lookup and lookup[key] != nt:
            continue  # ambiguous/conflicting entry, ignore rather than guess
        lookup[key] = nt
    print(f"Kugou reference: {len(lookup)} usable entries, {skipped} skipped (no ' - ' or degenerate)")
    return lookup


def rename_to_match_tags(path: Path, title: str, artist: str):
    new_base = sanitize(f"{title} - {artist}" if artist else title)
    new_name = f"{new_base}{path.suffix}"
    if new_name == path.name:
        return
    candidate_base, n = new_base, 2
    while True:
        target = path.with_name(f"{candidate_base}{path.suffix}")
        if not target.exists() or target == path:
            break
        candidate_base = f"{new_base} ({n})"
        n += 1
    path.rename(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--kugou", required=True, help="folder of original Kugou downloads (.kgg/.kgm/etc)")
    parser.add_argument("--library", required=True, help="working music library to fix")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    kugou_dir = Path(args.kugou)
    library_dir = Path(args.library)
    lookup = build_kugou_lookup(kugou_dir)

    checked = matched = swapped = already_ok = failed = 0
    for p in sorted(library_dir.rglob("*")):
        if not p.is_file() or p.suffix.lower() not in AUDIO_EXTENSIONS:
            continue
        checked += 1
        try:
            audio = MutagenFile(p, easy=True)
            title = (audio.get("title") or [""])[0] if audio and audio.tags else ""
            artist = (audio.get("artist") or [""])[0] if audio and audio.tags else ""
            if not title or not artist:
                continue
            nt, na = normalize(title), normalize(artist)
            if nt == na:
                continue
            key = frozenset((nt, na))
            correct_title_norm = lookup.get(key)
            if correct_title_norm is None:
                continue
            matched += 1
            if correct_title_norm == nt:
                already_ok += 1
                continue

            # Reversed: current "title" is actually playing the artist role.
            new_title, new_artist = artist, title
            if args.dry_run:
                print(f"  [dry-run swap] {p.name}  ->  title={new_title!r} artist={new_artist!r}")
                swapped += 1
                continue
            audio["title"] = new_title
            audio["artist"] = new_artist
            audio.save()
            rename_to_match_tags(p, new_title, new_artist)
            swapped += 1
        except Exception as e:
            print(f"  [fail] {p.name}: {e}", file=sys.stderr)
            failed += 1

    verb = "would swap" if args.dry_run else "swapped"
    print(f"\nchecked {checked} library files, matched {matched} against Kugou "
          f"({already_ok} already correct, {verb} {swapped}), failed {failed}")


if __name__ == "__main__":
    main()

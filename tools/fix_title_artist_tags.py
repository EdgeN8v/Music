#!/usr/bin/env python3
"""
Derives Title/Artist ID3 tags from each file's name and writes them back —
for a library where files were never tagged (or had those tags stripped),
so Navidrome/the app end up showing whatever it feels like for missing
fields (its own literal "[Unknown Artist]" placeholder once any tag frame
exists) instead of a clean 歌名/歌手 split.

Assumes the dominant convention in this library: "艺人 - 歌名.mp3". Splits
on the LAST " - " in the filename (handles a title that itself contains a
hyphen, e.g. "1492 - Conquest Of Paradise - Blake.mp3" → artist "Blake",
title "1492 - Conquest Of Paradise"), so artist = the piece after that
split, title = everything before it.

That assumption flips in two reliable-enough-to-auto-correct cases, both
verified by hand against this actual library before trusting them:
  - the piece after the split contains "、" (the separator always used to
    join multiple artist names — real titles essentially never contain it)
    → that's the artist list, so the piece before it is the title. This
    library names multi-artist collabs "歌名 - 艺人1、艺人2.mp3" (title
    first) — e.g. "有点甜 - 汪苏泷、BY2".
  - the piece before the split has a title-shaped qualifier like "(Live)"/
    "(Remix)"/"(Cover)"/"(Original Mix)"/etc — e.g. "不染 (Live) - 毛不易"
    (毛不易 is the artist, "不染 (Live)" the title) — swapped the same way.

One lookalike case is handled specially: "纯音乐"/"现场版"/"伴奏"/"dj版"
also show up as qualifiers, but when one of those words is the *entire*
"artist" side with nothing else attached, this library is using it as a
pseudo-artist tag for instrumentals instead — e.g. "纯音乐 - Here We Are
Again" is correctly artist="纯音乐", title="Here We Are Again" as-is, not
reversed. Attached to other text ("Love9 (纯音乐)", "一个人挺好 (DJ版)") it
swaps like the rest.

Every file that ends up swapped from the plain "artist - title" default
gets logged to the review CSV — not because they're shaky, but so there's
a record of what changed to skim afterwards if something looks off.

Usage:
    python fix_title_artist_tags.py /path/to/music --review swapped.csv
    python fix_title_artist_tags.py /path/to/music --review swapped.csv --dry-run

Requires: pip install mutagen
"""
import argparse
import csv
import re
import sys
from pathlib import Path

AUDIO_EXTENSIONS = {".mp3", ".flac", ".m4a", ".ogg", ".wav"}

# Confident enough to auto-swap (verified by hand against this library — see
# module docstring), as opposed to AMBIGUOUS_MARKER below which only flags.
AUTO_SWAP_MARKER = re.compile(
    r"remix|acoustic|\blive\b|extended|radio edit|version|reverse|vip|cover|mix\)|edit\)",
    re.IGNORECASE,
)
# Same shape of signal as AUTO_SWAP_MARKER, but this library also uses these
# words bare, standing alone, as a pseudo-artist tag (BARE_PSEUDO_ARTIST) —
# so whether it means "swap" depends on whether anything else is attached.
AMBIGUOUS_MARKER = re.compile(r"纯音乐|现场版|伴奏|dj版", re.IGNORECASE)
BARE_PSEUDO_ARTIST = {"纯音乐", "现场版", "伴奏", "dj版"}


def split_artist_title(stem: str) -> tuple[str, str, bool]:
    """Returns (artist, title, swapped) — swapped is just for the audit log."""
    if " - " not in stem:
        return "", stem, False
    left, right = (p.strip() for p in stem.rsplit(" - ", 1))
    if "、" in right or AUTO_SWAP_MARKER.search(left):
        # Confident swap: multiple artist names landed on the wrong side, or
        # the "title" side has a title-shaped qualifier the "artist" side
        # doesn't. Either way, the piece before the split is the real title.
        return right, left, True
    if AMBIGUOUS_MARKER.search(left) and left.lower() not in BARE_PSEUDO_ARTIST:
        # The marker shows up attached to other text (e.g. "Love9 (纯音乐)",
        # "一个人挺好 (DJ版)") rather than standing alone — a title qualifier, so swap.
        return right, left, True
    # Either no marker at all, or the whole "artist" side IS just e.g. "纯音乐"
    # — this library's pseudo-artist tag for instrumentals, not reversed.
    return left, right, False


def iter_audio_files(root: Path):
    for path in sorted(root.rglob("*")):
        if path.suffix.lower() in AUDIO_EXTENSIONS:
            yield path


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", help="music library root to scan recursively")
    parser.add_argument("--review", default="swapped.csv", help="CSV to log swapped-from-default filenames to (default: swapped.csv)")
    parser.add_argument("--dry-run", action="store_true", help="print what would be written without touching any file")
    args = parser.parse_args()

    from mutagen import File as MutagenFile

    root = Path(args.folder).expanduser()
    files = list(iter_audio_files(root))
    print(f"found {len(files)} audio files under {root}")

    swapped_log = []
    tagged = failed = 0
    for i, path in enumerate(files, 1):
        artist, title, swapped = split_artist_title(path.stem)
        if swapped:
            swapped_log.append((path.name, artist, title))

        if args.dry_run:
            flag = "  [swapped]" if swapped else ""
            print(f"  [{i}/{len(files)}] title={title!r} artist={artist!r}{flag}")
            continue

        try:
            audio = MutagenFile(path, easy=True)
            if audio is None:
                raise ValueError("unsupported/unrecognized audio format")
            if audio.tags is None:
                audio.add_tags()
            audio["title"] = title
            if artist:
                audio["artist"] = artist
            audio.save()
            tagged += 1
        except Exception as e:
            print(f"  [fail] {path.name}: {e}", file=sys.stderr)
            failed += 1

    if not args.dry_run:
        print(f"\ntagged {tagged}, failed {failed}")
        print("Now trigger a library rescan in Navidrome so the app sees the new tags.")

    review_path = Path(args.review)
    with review_path.open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["文件名", "写入的艺人", "写入的歌名"])
        w.writerows(swapped_log)
    print(f"{len(swapped_log)} file(s) were swapped from the plain default split — see {review_path}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Split a markdown file on ## (h2) headings into one file per chapter.

Chapters are any line matching ## ... in document order. Filename slugs are
derived from the heading text (an optional leading "N. " is stripped). Each ##
becomes a separate file; there is no merging of headings that share a number.

Usage:
  python split-markdown-chapters.py <source> <out_dir>

Example:
  python scripts/split-markdown-chapters.py specs/Pursue-Backend-Server-Spec.md specs/backend
"""
import argparse
import re
import sys
from pathlib import Path


def heading_to_slug(heading: str) -> str:
    """Derive a filesystem-safe slug from a ## heading."""
    # Strip optional leading "N. "
    s = re.sub(r"^\d+\.\s*", "", heading).strip()
    s = s.lower()
    s = re.sub(r"[^a-z0-9]+", "-", s)
    s = re.sub(r"-+", "-", s).strip("-")
    return s if s else "chapter"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Split a markdown file on ## headings into one file per chapter."
    )
    parser.add_argument(
        "source",
        type=Path,
        help="Path to the markdown file",
    )
    parser.add_argument(
        "out_dir",
        type=Path,
        help="Output directory for chapter files",
    )
    args = parser.parse_args()

    source = args.source.resolve()
    out_dir = args.out_dir.resolve()

    if not source.exists():
        sys.exit(f"Source file does not exist: {source}")
    if not source.is_file():
        sys.exit(f"Source is not a file: {source}")

    text = source.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)

    # Find all ## ... (top-level chapters) as (line_1based, heading_text)
    chapter_starts = []
    for i, line in enumerate(lines):
        m = re.match(r"^## (.+)$", line)
        if m:
            chapter_starts.append((i + 1, m.group(1).strip()))

    if not chapter_starts:
        sys.exit("No ## chapters found.")

    out_dir.mkdir(parents=True, exist_ok=True)

    for i, (start_line, heading) in enumerate(chapter_starts):
        end_line = chapter_starts[i + 1][0] if i + 1 < len(chapter_starts) else len(lines) + 1
        chunk = "".join(lines[start_line - 1 : end_line - 1])
        slug = heading_to_slug(heading)
        out_path = out_dir / f"{i + 1:02d}-{slug}.md"
        out_path.write_text(chunk, encoding="utf-8")
        print(f"Wrote {out_path} ({end_line - start_line} lines)")

    print(f"\nDone. {len(chapter_starts)} files in {out_dir}")


if __name__ == "__main__":
    main()

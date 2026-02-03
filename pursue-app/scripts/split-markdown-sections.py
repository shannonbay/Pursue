#!/usr/bin/env python3
"""
Split a markdown file on ### (h3) headings into one file per section.

Sections are saved to a subfolder named after the input file (without extension).
Each ### heading becomes a separate file.

Usage:
  python split-markdown-sections.py <source>

Example:
  python scripts/split-markdown-sections.py specs/ui/04-screen-specifications.md
  # Creates: specs/ui/04-screen-specifications/4.1-first-time-user-experience.md
  #          specs/ui/04-screen-specifications/4.2-home-screen.md
  #          etc.
"""
import argparse
import re
import sys
from pathlib import Path


def heading_to_slug(heading: str) -> str:
    """Derive a filesystem-safe slug from a ### heading."""
    # Strip optional leading "N.N " pattern (e.g., "4.1 ")
    s = re.sub(r"^\d+\.\d+\.?\s*", "", heading).strip()
    # Also handle single number patterns like "4. " or "4 "
    s = re.sub(r"^\d+\.?\s*", "", s).strip()
    s = s.lower()
    s = re.sub(r"[^a-z0-9]+", "-", s)
    s = re.sub(r"-+", "-", s).strip("-")
    return s if s else "section"


def extract_section_number(heading: str) -> str:
    """Extract number prefix from heading (e.g., "4.1 First-Time" -> "4.1")."""
    # Match patterns like "4.1", "4.1.", "4.2.3", etc.
    match = re.match(r"^(\d+(?:\.\d+)*)\.?\s+", heading)
    if match:
        return match.group(1)
    return ""


def get_output_dir(source_path: Path) -> Path:
    """Get output directory based on source filename (without extension)."""
    # Extract filename without extension
    filename_stem = source_path.stem
    # Return parent directory / filename (without ext)
    return source_path.parent / filename_stem


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Split a markdown file on ### headings into one file per section."
    )
    parser.add_argument(
        "source",
        type=Path,
        help="Path to the markdown file",
    )
    args = parser.parse_args()

    source = args.source.resolve()

    if not source.exists():
        sys.exit(f"Source file does not exist: {source}")
    if not source.is_file():
        sys.exit(f"Source is not a file: {source}")

    text = source.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)

    # Find all ### ... (h3 sections) as (line_1based, heading_text)
    section_starts = []
    for i, line in enumerate(lines):
        m = re.match(r"^### (.+)$", line)
        if m:
            section_starts.append((i + 1, m.group(1).strip()))

    if not section_starts:
        sys.exit("No ### sections found.")

    # Get output directory based on source filename
    out_dir = get_output_dir(source)
    out_dir.mkdir(parents=True, exist_ok=True)

    for i, (start_line, heading) in enumerate(section_starts):
        end_line = section_starts[i + 1][0] if i + 1 < len(section_starts) else len(lines) + 1
        chunk = "".join(lines[start_line - 1 : end_line - 1])
        
        # Extract section number and slug
        section_number = extract_section_number(heading)
        slug = heading_to_slug(heading)
        
        # Build filename
        if section_number:
            filename = f"{section_number}-{slug}.md"
        else:
            # Use sequential numbering if no number prefix
            filename = f"{i + 1:02d}-{slug}.md"
        
        out_path = out_dir / filename
        out_path.write_text(chunk, encoding="utf-8")
        print(f"Wrote {out_path} ({end_line - start_line} lines)")

    print(f"\nDone. {len(section_starts)} files in {out_dir}")


if __name__ == "__main__":
    main()

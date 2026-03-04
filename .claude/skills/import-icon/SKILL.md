---
name: import-icon
description: This skill should be used when the user asks to "import an icon", "add a new icon", "add icon assets", "generate icon densities", "process an icon for Android", or needs to take a source PNG and produce all Android density drawables for it in the Pursue project.
---

# Import Icon

Takes a source PNG placed in `images/` and produces all five Android density drawables, then commits everything.

## Overview

Two scripts at the project root handle the full pipeline:

- **`scripts/fix_icon_borders.py`** — Replaces a 20px solid border with transparency on every `ic_icon*.png` in `images/`. Idempotent (safe to re-run on already-fixed icons).
- **`scripts/generate_densities.py`** — Resizes every `ic_icon*.png` in `images/` to all five Android densities and writes them to `pursue-app/app/src/main/res/drawable-{density}/`.

Both scripts run on **all** `ic_icon*` files in `images/`. This is intentional and safe — existing icons are unaffected.

## How to Use

### Step 1 — Place the source image

The source PNG must be in `images/` and named `ic_icon_<name>.png` (e.g. `ic_icon_focus.png`).

If the file is not already there, ask the user where it is or check for it at the project root (`/images/`).

### Step 2 — Run the scripts (from project root)

```bash
python scripts/fix_icon_borders.py
python scripts/generate_densities.py
```

Both must complete without errors. If a script fails, check that Pillow is installed (`pip install Pillow`).

### Step 3 — Verify outputs

Confirm five new files exist for the icon:
```
pursue-app/app/src/main/res/drawable-mdpi/ic_icon_<name>.png      (64×64)
pursue-app/app/src/main/res/drawable-hdpi/ic_icon_<name>.png      (96×96)
pursue-app/app/src/main/res/drawable-xhdpi/ic_icon_<name>.png     (128×128)
pursue-app/app/src/main/res/drawable-xxhdpi/ic_icon_<name>.png    (192×192)
pursue-app/app/src/main/res/drawable-xxxhdpi/ic_icon_<name>.png   (256×256)
```

Use `git status --short | grep ic_icon_<name>` to check — only the new icon's files should appear as untracked.

### Step 4 — Commit (from pursue-app/ git repo)

The icon assets live in the Android repo (`pursue-app/`), which is a **separate git repo** from the backend. Always `cd pursue-app` before committing icon changes.

Stage only the new icon's files:
```bash
cd pursue-app
git add \
  ../images/ic_icon_<name>.png \
  app/src/main/res/drawable-mdpi/ic_icon_<name>.png \
  app/src/main/res/drawable-hdpi/ic_icon_<name>.png \
  app/src/main/res/drawable-xhdpi/ic_icon_<name>.png \
  app/src/main/res/drawable-xxhdpi/ic_icon_<name>.png \
  app/src/main/res/drawable-xxxhdpi/ic_icon_<name>.png
```

Commit message format:
```
feat(assets): add ic_icon_<name> drawable at all densities
```

## Notes

- The drawable is referenced in group template migrations as `res://drawable/ic_icon_<name>` (no `.png` extension, no path prefix).
- Do **not** use `git add .` — the scripts regenerate all icons and you only want to stage the new one.
- The `images/` source file lives in the Android repo even though it's at the monorepo root (one directory above `pursue-app/`). The `../images/` path in the `git add` command is correct.

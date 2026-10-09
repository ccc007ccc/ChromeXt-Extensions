#!/usr/bin/env python3
"""Package the repository's standard WebExtension fixture, without executing it."""
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
source = root / "examples" / "page-marker"
destination = root / "build" / "examples" / "page-marker.zip"
destination.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(source.rglob("*")):
        if path.is_file():
            archive.write(path, path.relative_to(source).as_posix())
print(destination)

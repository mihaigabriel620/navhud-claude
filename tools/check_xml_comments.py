#!/usr/bin/env python3
"""
A double hyphen is illegal inside an XML comment, and aapt reports it as a bare
"ParseError at [row,col]" with no explanation. This has cost four build failures
on this project, always in a comment written the way one writes prose -- like
that. Run it before building.
"""
import re
import sys
import pathlib

bad = []
SKIP = ("/build/", "/.gradle/", "/intermediates/")

for p in pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").rglob("*.xml"):
    # Build outputs are copies of the sources, so a fixed file kept reporting
    # itself from last build's intermediates.
    if any(s in str(p.as_posix()) for s in SKIP):
        continue
    text = p.read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(r"<!--(.*?)-->", text, re.S):
        if "--" in m.group(1):
            line = text[: m.start()].count("\n") + 1
            bad.append(f"{p}:{line}: '--' inside an XML comment")

for b in bad:
    print(b)
sys.exit(1 if bad else 0)

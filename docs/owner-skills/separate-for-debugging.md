---
name: "separate-for-debugging"
description: "Split code into files by job so problems are easy to find and debug. Use when a main file grows past ~400-500 lines or mixes unrelated jobs, or when asked to organise/separate code."
---

## Rules
- **One file per job you would debug on its own:** e.g. a hardware driver, a device you talk to, a feature, a sensor, diagnostics.
- **The main file only orchestrates:** startup, main loop, and passing events to the right file. No feature logic in it.
- **Put risky code in as few files as possible:** anything that talks to the outside world (sends on a bus, writes data, controls hardware). When something goes wrong, you check one or two files.
- **No dumping-ground files:** no `utils`, `helpers`, `misc`, or abstract names like "Safety". A thing lives where it is used.
- **Small, clear connections:** each file shows only what others need; share data through function parameters and return values, not global variables used everywhere.
- **Don't over-split:** a few focused files, not dozens of tiny ones.

## Make It Findable
- **Name files after their job** (`CanBus`, `Amp`, `Vent`, `Battery`).
- **Put a file map at the top of the main file:** each file -> what it does, plus "if this breaks -> look in this file".

## When Splitting Existing Code
- Only split code that already works, and never at the same time as fixing bugs.
- Move one file at a time, code unchanged, and build/test after each move.
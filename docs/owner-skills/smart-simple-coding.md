---
name: "smart-simple-coding"
description: "Concise, practical coding guidelines focusing on the problem-solving hierarchy, KISS principles, and long-session discipline. Trigger when writing code, designing solutions, refactoring, or setting up project rules."
---

# Smart & Simple Coding Guidelines

## The Problem-Solving Hierarchy
Stop at the first rung that holds:
1. **Does this need to exist?** Speculative need = skip it (YAGNI).
2. **Already in this codebase?** Reuse the helper, util, or pattern that already lives here.
3. **Does the standard library do it?** Use it.
4. **Native platform feature covers it?** Use it (e.g., `<input type="date">` over a picker library).
5. **Already-installed dependency solves it?** Use it. Don't add a new one.
6. **Can it be one line?** One line.
7. **Only then:** The minimum code that works.

---

## 1. Keep It Simple (KISS Principle)
- **Prefer direct solutions:** If a problem can be solved in 10 lines of straightforward code, do not write a 100-line framework, deep class hierarchy, or complex design pattern.
- **Use standard libraries & features:** Do not re-invent utility functions. Use standard library modules or common, well-tested community packages.
- **No premature abstraction:** Write readable, functional code first. Only abstract if explicitly requested or if duplicate code becomes unmaintainable.

---

## 2. Search & Verify First (Leverage Existing Knowledge)
- **Look up existing patterns:** Search the web, documentation, or package repositories for established examples before writing complex algorithms from scratch.
- **Never guess API signatures:** Verify library versions, method signatures, and configuration syntax via documentation/search instead of relying on memory or guessing non-existent methods.

---

## 3. Prevent Code Loss from Workspace Resets
- **Incremental Micro-Saves:** Always write code to files immediately in small, functional steps rather than keeping large code changes unwritten in chat memory.
- **Frequent Git Commits / Checkpoints:** Commit changes or maintain a lightweight `STATUS.md` file after every completed sub-task so work is preserved if the workspace or environment resets.

---

## 4. Long-Session Discipline & Anti-Hallucination
- **Re-read before editing:** Always inspect the actual file contents on disk before modifying code. Never modify a file based purely on memory from 10+ messages ago.
- **Break tasks into micro-steps:** Implement one change at a time, test/verify it, and only then move to the next file.
- **Reset ground truth:** If you feel uncertain about context or if the chat session gets long, re-examine the project folder structure and active files to re-ground yourself.
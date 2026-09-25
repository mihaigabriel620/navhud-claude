---
name: "systematic-debugging"
description: "A disciplined, hypothesis-driven debugging workflow. Trigger when troubleshooting unexpected behavior, fixing broken code, or diagnosing system failures to prevent trial-and-error thrashing."
---

# Systematic Debugging & Root-Cause Analysis

## 1. Stop and Replicate (The Baseline)
- **Do not guess the fix:** Never write code to fix a bug until you can consistently reproduce it.
- **Isolate the environment:** Strip away complex integrations. Run the smallest possible script, test, or isolated circuit/hardware state that triggers the issue.

## 2. Formulate a Hypothesis (No Trial-and-Error)
- **State the assumption:** Before changing any code, explicitly state what you think is wrong (e.g., "I believe the variable is returning null because the API call times out").
- **Test the assumption:** Write a print statement, log, or assertion to prove your hypothesis *before* attempting a fix.

## 3. Verify Ground Truth (Trust Nothing)
- **Check the raw data:** Do not trust the UI, the wrapper library, or the downstream error message. Log the raw input, the raw output, and the exact hardware/memory state at the exact moment of failure.
- **Trace the data flow:** Walk backward from the error line. Prove that the data entering the function is exactly what you expect it to be.

## 4. The One-Change Rule
- **Modify one variable at a time:** Change exactly one line of code, configuration, or environment variable. 
- **Observe the delta:** Run the test. Did the error change? Did it disappear?
- **Revert failed attempts:** If a change does not fix the issue or explain the behavior, **undo it immediately**. Never stack experimental changes on top of each other.

## 5. Re-Grounding (When stuck for >15 minutes)
- **Walk away from the code:** Stop looking at the complex logic.
- **Read the docs/specs:** Verify the baseline assumptions about the library, API, or datasheet. 
- **Explain it to the rubber duck:** Write out a plain-English explanation of exactly what the system is doing versus what it should be doing. The disconnect is usually in the foundational logic, not a missing semicolon.
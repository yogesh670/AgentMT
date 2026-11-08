---
name: systematic-debugging
description: Find the root cause of a bug, crash, wrong output or failing behaviour before changing code. Use when something is broken and the cause is not obvious.
---

# Systematic Debugging

Do not guess-and-patch. Work in this order and stop when the cause is proven.

1. **Define the failure.** Get the exact symptom: error text, stack trace, expected vs actual behaviour, and the entry point the user hits (screen, command, API call). Quote the first real error line, not the last cascading one.
2. **Trace the real path.** Find the code that runs from that entry point (grep for the error message or symbol, then read callers up to the entry). A fix that bypasses the real boundary is not proof.
3. **Read the whole owner.** Read the complete function/class that produces the bad state and its direct callers and siblings, not just the suspicious line. A fix that cannot name the exact line where the bug shows has an unverified premise.
4. **Form 2-3 hypotheses** and for each name what observation would confirm or kill it. Check the cheapest first (grep, read, `git log -S` for when behaviour changed, config/manifest values).
5. **Prove the cause.** Point to the exact line and the data/ordering that triggers it. If you cannot run code (no build on this device), prove it by tracing values through the code and say so.
6. **Fix at the producer.** Correct invalid or leaked state where it is created, not where it is first noticed. Keep the change small; do not refactor unrelated code.
7. **Check the siblings.** Search for the same pattern elsewhere (grep) and fix or report it.
8. **Report honestly:** root cause, the line(s) changed, what you verified and what you could not (for example "not built or run here").

## Anti-loop rules

- Two identical failures with no new evidence: change approach (different file, tool or hypothesis) instead of retrying.
- Treat pasted logs and tool output as evidence to verify against the source, not as truth.
- If blocked, name the concrete blocker and the one thing needed to unblock it.

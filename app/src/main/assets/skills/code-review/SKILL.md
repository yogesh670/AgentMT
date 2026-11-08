---
name: code-review
description: Review code changes or a file for bugs, regressions, security and maintainability. Use when the user asks to review, audit or double-check code or a diff.
---

# Code Review

1. **Scope.** Identify exactly what to review (files, a diff via `git diff`, or a feature). Read the complete changed files plus the callers/tests they touch; do not review from snippets.
2. **Intent.** State in one line what the change is supposed to do and judge it against that.
3. **Look for, in priority order:**
   - Correctness: wrong conditions, off-by-one, null/empty cases, error paths, ordering and concurrency (threads/coroutines, Android lifecycle), resource leaks.
   - Regressions: other callers or siblings affected by changed signatures, defaults or behaviour.
   - Security/privacy: secrets in code or logs, unvalidated input, unsafe file/URL handling, over-broad permissions.
   - Data safety: migrations, persistence format changes, destructive operations without confirmation.
   - Maintainability: duplicated logic, dead code left behind, unclear ownership of state, needless complexity.
4. **Verify before claiming.** Every finding must cite `file:line` and the concrete scenario that breaks. Drop findings you cannot support; do not pad the list with style nits.
5. **Report** grouped as Blocking / Should fix / Optional, each with file:line, the problem and a minimal suggested fix. End with what you could not verify (for example "not compiled or run on this device").

Do not modify code during a review unless the user asks for the fixes.

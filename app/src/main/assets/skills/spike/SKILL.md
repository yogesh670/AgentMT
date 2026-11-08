---
name: spike
description: Run throwaway prototypes to validate feasibility, compare approaches, and report a verdict before building for real.
---

# Spike

Use when the user wants to test an idea before committing: "spike this", "quick prototype", "is this possible", "compare A and B", "before we build".
Do not use when reading docs or code already answers the question, or when the user clearly asked for the production implementation.

## Loop

1. **Question** - state the one concrete feasibility question.
2. **Research** - read just enough code/docs (grep, read) to choose a credible approach.
3. **Build** - create the smallest artifact that proves or disproves the idea. Put it in `.tmp/spikes/<slug>/` unless the user wants a tracked path.
4. **Stress** - try one edge case or failure mode.
5. **Verdict** - `VALIDATED`, `PARTIAL` or `INVALIDATED`.

On this device you cannot build APKs. If the spike needs a build, describe exactly what the user should run and what output would confirm or refute the idea.

## Verdict format

```
## Verdict: VALIDATED | PARTIAL | INVALIDATED
Question: ...
Evidence: exact command/output/measurement (or "not runnable here" + what to check)
What worked: ...
What failed or surprised us: ...
Recommendation: ship / adjust / avoid, plus the next production step
```

## Rules

- An invalidated spike is a good result when it rules out a path with evidence.
- Never merge spike code into production as-is; rewrite it normally.
- Several ideas: split into 2-5 independent questions and do the riskiest first. Keep inputs equal for A/B comparisons.

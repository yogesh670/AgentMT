---
name: skill-creator
description: Create, repair or review agent skills (SKILL.md files) for this project, with a good trigger description and lean instructions.
---

# Skill Creator

A skill is a folder with a `SKILL.md` (and optional helper files). Only its `name` and `description` are always visible to the agent, so the description decides when it gets loaded.

## Where to put it

Create project skills at `.agents/skills/<skill-name>/SKILL.md` in the working directory (lowercase letters, digits and hyphens for the folder name). They are discovered automatically in the next session.

## SKILL.md format

```
---
name: my-skill
description: One sentence saying WHAT it does and WHEN to use it, with the words a user would actually say.
---

# Title
Short, imperative instructions.
```

## Writing rules

1. **Description is the trigger.** Include the situations and keywords that should load it. Add a "do not use for" clause when neighbours could be confused.
2. **Keep the body short** (under ~150 lines). The agent already knows general programming; write only what it would not guess: project conventions, exact commands, gotchas, output formats.
3. **One job per skill.** Split unrelated workflows.
4. **Put bulky reference material in extra files** next to SKILL.md (e.g. `reference.md`) and link to them; they are listed when the skill loads and read on demand.
5. **Be concrete**: commands, file paths, checklists and examples beat prose.
6. **No secrets** and no machine-specific absolute paths.

## Review checklist

- Does the description say when to use it and when not to?
- Is every instruction something the agent could not infer alone?
- Are the steps ordered, and does each have a way to verify it worked?
- Is the frontmatter valid (`---` delimiters, `name`, `description`)?

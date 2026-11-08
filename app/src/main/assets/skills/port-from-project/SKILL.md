---
name: port-from-project
description: Copy features, UI screens or functions from another project folder into the current project while following the current project's structure. Use when the user wants to copy, port, migrate or reuse something from project A in project B.
---

# Port From Project

Use when the user points at another project folder (the SOURCE) and wants its features, UI or logic rebuilt in the current project (the TARGET). The slash command `/port <source folder> [what]` starts the same workflow.

1. **Study the TARGET first.** Read build files, version catalog, manifest, AGENTS.md/README, package/module layout, naming, architecture (MVVM, DI, navigation, repositories), UI toolkit, theme tokens and shared components.
2. **Study the SOURCE (read-only).** Map each requested feature: screens, view models/state, data models, network/db code, resources (strings, drawables, fonts), manifest entries/permissions, dependencies. If the SOURCE is outside the working directory, the user may need to approve access once.
3. **Plan** with todowrite: for each source element choose its place in the TARGET structure, reuse existing target code instead of duplicating, list dependencies/resources/permissions to add.
4. **Port in small steps** (at most ~200 lines per write/edit call). Adapt package names, imports, naming, theme tokens and components; register screens in the TARGET's navigation; wire dependencies like the TARGET does; add Gradle/manifest/resource entries in the TARGET's style and versions. Merge into existing files after reading them; never overwrite blindly.
5. **Verify** with diagnostics and read-back; make sure imports, symbols, resource ids and dependencies resolve. State what could not be verified (for example not built on this device).
6. **Report:** what was ported, source-to-target file mapping, dependencies/permissions added, skipped items and why, manual steps left.

Never modify the SOURCE folder. Keep the TARGET's existing behavior working.

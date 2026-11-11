---
name: reverse-engineer-anything
description: Reverse engineer shipped artifacts (native binaries, APK/IPA, JavaScript/Electron, firmware, packages) with the REA toolset, or fall back to JADX/apktool inside AgentMT's Linux. Use when a question depends on a binary or package rather than available source. Skip for ordinary source-repository analysis.
---

# Reverse engineering (REA)

Adapted for AgentMT from rea's `reverse-engineer-anything` skill
(https://github.com/morluto/rea, MIT). Keep this file self-contained: the
supporting `references/` files are not bundled, so use the REA MCP tools or the
CLI, or the local fallbacks below.

Use this when the answer depends on a **shipped artifact** — a native binary, an
APK/IPA/MSIX, a JavaScript/Electron bundle, firmware, or a packaged executable —
and source is missing, partial, or suspect. For ordinary architecture questions
about a source repository you already have, use the normal repo tools and do not
run REA.

## Connect REA

Installing this skill supplies instructions only; it does not register a server.
Two ways to actually run REA:

1. **MCP (preferred).** If an `rea` MCP server is configured, call its tools
   directly. In AgentMT this is usually `linux:rea mcp` (stdio inside Linux), set
   up from Menu → Linux (the "Reverse engineering" kit installs `rea-agents`).
   Use the connected server's **actual** tool list and input schemas; this file
   may describe tools an older release lacks.
2. **CLI fallback.** With no MCP, run REA inside Linux and read the JSON it
   returns. Example for a JavaScript/ASAR tree:

   ```bash
   npx -y rea-agents@latest analyze-javascript-application /abs/path/to/app --json
   ```

   Prefer an already-installed `rea`; only fetch the package when that is in
   scope. Always pass **absolute** target paths and run from a trusted directory
   outside the target. Native CLI tasks still need their engine (Ghidra/JADX)
   installed.

If tools are missing or a call is blocked, do not loop setup: report the exact
blocker, continue any supported work, and ask the user to restart the MCP client
or check the engine.

## Route the target first

Pick the first tool from what the user handed you.

| Target | Start with |
| --- | --- |
| Android APK: classes, methods, incoming refs | `inspect_android_package`, then focused Android tools |
| APK signature / signing certs | `inspect_apk_signature` |
| ZIP/APK/IPA/MSIX/DMG/PyInstaller archive inventory | `open_binary` (then `inspect_artifact` for the graph) |
| ASAR or extracted JS/Electron tree | `analyze_javascript_application` |
| Managed PE / .NET assembly | `inspect_managed_artifact` |
| Firmware image | `inspect_firmware_regions`, `extract_firmware` |
| Native ELF/PE layout, exports, hardening | `inspect_binary_layout`, `inspect_native_exports`, `inspect_binary_hardening` |
| One native function | `analyze_function` (summary-first by default) |

`open_binary` sets the active target for later native/archive calls; target-free
tools take their own explicit path. Use `detail: "summary"` for anything large,
then page the retained result with `inspect_analysis_view`.

## Work summary-first

1. Start from the default result; read its inline Evidence and graph context.
2. For large inputs, request `detail: "summary"` and drill in with
   `inspect_analysis_view` using the returned `parent_evidence_id`. Request
   `detail: "complete"` only when the whole record must be inline.
3. Do not repeat an identical analysis. Make one focused follow-up only when the
   result leaves a specific question open.
4. If a result is truncated (transport or client preview), it is incomplete: use
   the retained parent ID for a smaller view, or export the bundle.
5. For multi-feature requests, keep a checklist of questions, reuse existing
   sessions/evidence, and work the entry point → data/state changes → result for
   each.

## Evidence discipline

- Separate **observations**, **inferences**, and **unknowns**. Cite Evidence IDs.
- Never imply static analysis saw execution; say what was actually observed.
- Preserve limitations and coverage gaps in your write-up.
- Reconstruct only what the evidence supports; mark the rest as unknown.

## Local fallback: Android APK without REA

If no REA/engine is available, decompile inside Linux with the bundled tools:

```bash
# install once (inside Linux): JADX + apktool via the Linux package manager
jadx -d /root/out /path/to/app.apk          # Java sources (readable, good for logic)
apktool d -f -o /root/res /path/to/app.apk  # resources + smali (good for manifest/strings)
```

- Read `AndroidManifest.xml`, resources and strings first to orient.
- Use `grep`/`glob` over the JADX output for entry points, keys, URLs, flags.
- Treat decompiled output as a **hypothesis**: verify with a second source
  (resources, strings, behavior) before asserting it.
- Do not copy third-party decompiled code into this project unless its license
  allows it; port ideas with a clean-room reimplementation.

## Finish

Explain findings in plain language, tie each claim to its evidence, state what
could not be determined, and give the user the next concrete step (a specific
file, address, tool, or follow-up question).

_REA is MIT-licensed (© the rea authors). This skill rewords it for AgentMT._

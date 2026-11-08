# AgentMT — design decisions

Each entry: what we chose, why, and what it replaces from the architecture pack v1.0.

## D-001 Real app, not a fixture shell (v0.2.0)
All screens now read and write real data. `FixtureStore` is gone. Anything that is not built is absent rather than shown as a working button.

## D-002 ChatGPT/Claude-style navigation
A left drawer (chats grouped by day, search, pin by long-press) replaces the four-tab bottom bar from docs/03. Chat is the home screen; Files, Runs, Tools and Settings open from the drawer. Reason: the owner asked for the feel of the ChatGPT / Claude mobile apps, where the conversation fills the screen.

## D-003 Storage: java.io.File + All files access, plus app-private projects
docs/12 specified SAF for every file operation. The agent's tools (glob, grep, edit, shell in the project folder) need real paths, and SAF DocumentFile is too slow for recursive search. So:
- New projects are created in the app's external files dir (no permission needed).
- Opening any other folder uses the SAF folder picker to choose it, converts it to a path, and requires MANAGE_EXTERNAL_STORAGE ("All files access"). This matches the reference app (ClozedCode).
- Every tool path is resolved inside the project root; escaping it is refused (Workspace.resolve).
Play Store note: MANAGE_EXTERNAL_STORAGE needs a policy declaration. Fine for sideloading and for a coding-agent use case.

## D-004 Manual DI
`AppContainer` builds Room, the Keystore vault, settings, skills and the agent engine. No Hilt/Koin yet; one container keeps the build simple and fast. Revisit if modules split.

## D-005 Persistence
Room (`agentmt.db`, schema exported to app/schemas): projects, sessions, messages, runs, providers, rules, mcp_servers. Settings in SharedPreferences behind a StateFlow. Secrets (API keys, MCP tokens) are AES-256-GCM encrypted with an Android Keystore key and never stored in Room or logs.

## D-006 Agent engine
`AgentEngine` is an app-scoped singleton so runs continue when you leave the chat. A foreground service (dataSync) plus a partial wake lock keeps the process alive during a run. Loop: stream → persist assistant message → for each tool call, check rules / approval mode → run → persist result → repeat until no tool calls or max steps.
Providers: OpenAI Chat Completions (works with any compatible endpoint: OpenRouter, Gemini, Groq, DeepSeek, Ollama…) and Anthropic Messages, both streaming with tool calls.

## D-007 Approvals
Three modes (ask before changes [default], ask for everything, auto-approve edits). Ordered rules (first match wins) override the mode. "Always allow" on an approval card creates an exact-target allow rule.

## D-008 MCP
Remote servers over Streamable HTTP (JSON or SSE responses), bearer token optional. Local stdio servers cannot run on Android and are not offered. MCP tools are exposed as `mcp__<server>__<tool>` and default to asking.

## D-009 Shell
`run_command` uses /system/bin/sh (toybox) in the project folder, always behind approval by default. No bundled git/node/gradle; the system prompt tells the model so.

## D-010 Visual identity
Dark default, near-black neutral background, blue primary, cyan secondary, amber for approvals. Icon: terminal chevron + cyan cursor + amber spark on a navy→blue gradient, as an adaptive icon with a monochrome layer. Master art in branding/.

## Not built yet (deliberately hidden)
Voice, remote build runner, git, attachments/images in chat, conversation compaction by summary, undo of file edits, provider export/import.

## v0.3.0 (2026-11-06)
- D-011 Multi-credential providers: keys live in `credentials` (metadata) + vault `cred:<id>`. Legacy `provider:<id>` keys migrate to a "Main" credential on first start.
- D-012 Routing: `RouteRunner` = bounded attempts (default 4, max 8), 180 s pre-first-token deadline, cooldown per key, skip invalid/cooling/capability-incompatible, no fallback after output starts, never after cancel. Pure Kotlin, unit-tested.
- D-013 Room v2 with explicit MIGRATION_1_2 (verified against exported 2.json). No destructive fallback.
- D-014 Workspace backup = zip with manifest, per-table JSON (column-by-column for forward compatibility), files, optional `secrets.enc` (PBKDF2-SHA256 210k + AES-256-GCM). Message/rule ids are reassigned on import.
- D-015 Plugins follow the Claude Code layout; installed disabled; never execute code.
- D-016 Logo drawn in Compose (theme-aware); splash uses values-night colours. Onboarding hero image removed in favour of a live agent demo.
- D-017 targetSdk/compileSdk 36, AGP 8.9.3, Gradle 8.11.1.

## D-018 Crash reporting is local (v0.4.0)
Uncaught exceptions are written to filesDir/last_crash.txt and shown on next launch with Copy. No analytics or network upload.

## D-019 JVM launch tests with Robolectric (v0.4.0)
No emulator in the build sandbox (aarch64, no KVM). AppLaunchTest drives the real MainActivity via Robolectric (LEGACY graphics, Conscrypt off).
Room 2.7 lets tests swap in the bundled SQLite driver (system property agentmt.sqliteDriver) because Robolectric's native SQLite has no linux-aarch64 build. The app itself keeps the platform SQLite.

## D-020 Built-in terminal + Linux via proot (v0.4.0)
Terminal UI = Termux terminal-view/terminal-emulator v0.118.1 (Apache-2.0, JitPack). Linux = Alpine minirootfs 3.24.4 (sha256-checked) run through Termux's proot 5.1.107 build, with libtalloc.
Binaries ship as lib*.so (jniLibs, useLegacyPackaging) because Android 10+ blocks exec from app data (W^X). NEEDED libtalloc.so.2 patched to libtalloc.so so the packager extracts it. arm64 only; other ABIs get the Android shell.
run_command defaults to the Linux environment once installed (environment=android to opt out).

## D-021 Build / Plan agents (from opencode review, v0.4.0)
Plan mode = read-only tool set (Read kinds + web_fetch), plus a system-prompt note. Toggle in the composer. /init writes AGENTS.md; /plan command.

## v0.4.1 — D-022 Terminal view init order
TerminalView.setTextSize must run before setTypeface (renderer created in setTextSize); crash on vivo 1938 / Android 12.

## v0.5.0 (2026-11-07)
- D-023 Linux distros: Ubuntu 24.04.5 (official ubuntu-base arm64 tarball, SHA-256 pinned) or Alpine 3.24.4, both via proot. Ubuntu preferred (glibc, apt). Fake /proc files bound when Android hides the real ones. Pure-JVM TarExtractor, tested on both real tarballs.
- D-024 On-phone APK builds: ApkBuildKit installs JDK 17 (distro pkg), Gradle 8.9, platform-35, build-tools 35 and lzhiyong static arm64 aapt2; ~/.gradle/gradle.properties sets aapt2FromMavenOverride. Agent told how via system prompt only when ready.
- D-025 Tools manager: GitHub repos cloned to /opt/tools/<name> (SKILL.md folders become skills), apt/apk packages, pip packages. Inputs validated by regex; manifest JSON for backup/share/import. Jobs serialized (apt lock).
- D-026 Built-in browser: one shared WebView (cookies shared with agent). Tools browser_read (Read) and browser (Network: open/click/type/scroll/back/run_js) using numbered data-agentmt-id elements.
- D-027 Extensions = userscripts (Tampermonkey format, GM_* shim). Chrome .crx extensions cannot run in Android WebView.
- D-028 Approval mode AutoAll; reasoning_effort sent to OpenAI-compatible APIs only (Anthropic thinking needs signed thinking blocks in tool loops, not supported); TTS voice on task done.

## v0.5.1 / v0.6.0 (2026-11-07)
- D-029 (v0.5.1) AgentService calls startForeground in onCreate and every onStartCommand; stop via "stop" intent with holders (HOLD_AGENT, HOLD_JOBS). Fixes ForegroundServiceDidNotStartInTimeException.
- D-030 Two flavors: `full` (GitHub releases: Linux, build kit, tools, All files access, battery exemption) and `play` (no proot, no runtime-downloaded executables, no MANAGE_EXTERNAL_STORAGE / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). Reason: Play's Device and Network Abuse policy bans downloading executable code; same reason Termux is not on Play.
- D-031 License rule: from GPL-3.0 projects (termux-app outside terminal-view/emulator, termux-tools, proot-distro, Termux:API, AntiGem) take ideas only, never code. Notices + full texts in assets/licenses, shown in Settings → Open source licenses. See LICENSE_AUDIT.md.
- D-032 libtermux.so rebuilt from unchanged Apache-2.0 termux.c with 16 KB page alignment (Play requirement); pickFirsts makes ours win over the AAR's 4 KB build.
- D-033 Clean-room proot-distro-style distro Back up / Restore / Reset (TarWriter + TarExtractor, .tar.gz) and termux-tools-style Android bridge commands (agentmt-open/-share/-clipboard-get/-set/-notify/-toast) via request files in a bound folder watched by FileObserver.

## D-034 (v0.7.0) Features from the user's ClozedCode demo videos
Videos watched via transcripts (yt-dlp is blocked from the build sandbox by YouTube). Added: context compaction ("summary" rows,
history kept visible, auto at ~60k tokens and on "context too long"), background commands + background_process tool for dev servers,
one-tap kits (Reverse engineering: apktool, radare2, jadx 1.5.6, REA skill; Web dev; Media: ffmpeg + pip yt-dlp), agentmt-install
(REQUEST_INSTALL_PACKAGES, full flavor only), paste a folder path as a project.

## D-035 (v0.8.0) Local stdio MCP via Linux, REA done properly, agent can install tools
- From the user's own screen recordings of ClozedCode (frames read): it installs skills from chat ("github.com/morluto/rea yah skill install karo"), lists npm/pip/git extensions, has "Limit flags reset", custom endpoint presets.
- REA is an npm package (rea-agents, needs Node >=22.23.4) whose MCP server is stdio (`rea mcp`). AgentMT now runs MCP servers written as `linux:<command>` inside proot, one long-lived process per command (StdioMcp), newline JSON-RPC, answers ping/roots.
- RE kit installs Node 22 from nodejs.org, `npm i -g rea-agents`, procps, and auto-adds MCP server "REA" = `linux:rea mcp`.
- New npm tool kind ("Node CLI"); new agent tool install_tool (github/linux/python/npm), Execute approval.
- Routing → Key health: "Reset limit flags" for all keys.
- Known gap: StdioMcp readLine blocks; the timeout is only checked between lines.

## D-036 (v0.8.1) Bundled provider catalog
- User supplied providers.json (from ClozedCode): 211 providers, 5,822 models, rows [id,name,context,tools,in,out,cacheRead,cacheWrite,maxOut]; no keys. Bundled at assets/catalog/providers.json (ProviderCatalog). Data appears models.dev-derived; source/license not verified — check before Play release.
- Providers → Add a provider: featured list + search across all catalog providers/models. Onboarding keeps featured only.
- Compaction threshold capped at 75% of the smallest known context window among candidate models.

## D-037 (v0.9.0, 2026-11-07) Use the user's own Termux; Continue instead of restart
- User already has node/python/packages in Termux (GitHub build) and wants AgentMT to use them. Added core/termux/TermuxBridge (public RUN_COMMAND intent, clean-room from the wiki; full flavor manifest declares com.termux.permission.RUN_COMMAND + <queries>). Setting useTermux; run_command environment "termux" (default when on). Termux sees only shared storage.
- MCP "termux:<cmd>": TermuxMcp starts `npx -y supergateway@4 --stdio <cmd> --outputTransport streamableHttp --port P --healthEndpoint /healthz` (supergateway MIT) in Termux and connects to http://127.0.0.1:P/mcp.
- Error card: Continue (keeps all finished steps, sends CONTINUE_TEXT, works after model switch) + Restart (old retry). Unanswered tool calls are already dropped by buildHistory.
- Not device-tested. Termux processes started with nohup may be killed if Termux is stopped; termux-wake-lock helps.

## D-038 (v0.9.1, 2026-11-07) Permissions in onboarding
- New onboarding step 4/5 "Permissions" (feature/onboarding/PermissionsStep.kt): approval mode for coding + phone access rows (all files, notifications, background/battery, install apps, Termux) with Allow buttons; status refreshes on resume. Granting Termux turns useTermux on. Checks wrapped in runCatching (Robolectric threw on isExternalStorageManager).

## D-039 (v0.9.2, 2026-11-08) Ship libandroid-shmem; guard native deps
- Device report: "CANNOT LINK EXECUTABLE libproot.so: library libandroid-shmem.so not found". Termux proot NEEDS libtalloc.so + libandroid-shmem.so; only talloc was bundled, so built-in Linux never ran on a phone (v0.4.0–v0.9.1). Added Termux libandroid-shmem 0.7 aarch64 (BSD-3-Clause, sha256 0da3a24d558b…, 16 KB aligned) to full jniLibs + license. LinuxEnv.supported requires it. NativeLibsTest parses DT_NEEDED of all bundled libs. TermuxBridge.SETUP now uses sed to force allow-external-apps=true.

## D-040 (v0.9.3, 2026-11-08) Never bind host "/" into proot
- Device: kits failed "proot error: '/usr/bin/env' not found (root = /, cwd = /)". LinuxJobs.run default cwd was File("/") and argv() bound every cwd with -b, so host / covered the rootfs. LinuxEnv.guestCwd(): no bind for /, parents of the rootfs, or shared storage; paths inside the rootfs are translated to guest paths. GuestCwdTest.

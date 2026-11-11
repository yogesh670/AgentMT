# AgentMT

A mobile-first AI coding agent for Android. Kotlin, Jetpack Compose, Material 3.

## What works
- Chat with streaming replies (OpenAI-compatible or Anthropic API), Markdown with highlighted, copyable code blocks.
- Real agent loop with tools: list_dir, read_file, glob, grep, write_file, edit_file, patch (validated multi-file diff), run_command, web_fetch, load_skill, diagnostics (JSON/XML/source syntax check), ask_user (pause for a user decision), plus remote MCP tools.
- Approval cards with diff preview; allow once / always / deny; permission rules.
- Projects: create inside the app, or open any phone folder (All files access).
- Files browser and editor with line numbers, HTML/Markdown preview.
- Runs history with timeline, tokens, changed files, stop.
- Providers with encrypted API keys, "Test & fetch models".
- Skills (SKILL.md) built in, per user, and per project (.agentmt/skills).
- Foreground service keeps runs alive in the background.

## Build
Open in Android Studio (Ladybug or newer) and run, or:
```
./gradlew :app:assembleDebug
```
JDK 17, compileSdk 35, minSdk 26.

## Layout
```
app/        AgentMTApp (drawer + navigation), AppContainer (DI), theme
agent/      AgentEngine, PermissionEngine, SystemPrompt, AgentService
agent/llm   OpenAiClient, AnthropicClient, SSE helpers
agent/tools Workspace + file/shell/web/skill/MCP tools
agent/mcp   Streamable HTTP MCP client
data/       Room DB, Keystore vault, settings
feature/    chat, files, runs, tools, providers, settings, onboarding, projects
branding/   icon master (SVG/PNG), Play Store icon 512, onboarding art
```
See DECISIONS.md for why things are the way they are.

## v0.4.0
- Crash reporter: last crash shown on next launch with Copy (core/CrashReporter.kt).
- Terminal (drawer → Terminal): Termux terminal view, tabs, extra keys row. Linux environment: Alpine via proot (core/linux/LinuxEnv.kt), `apk add` packages, quick-install chips. run_command uses Linux once installed.
- Build / Plan toggle in the composer (Plan = read-only). /init (writes AGENTS.md), /plan.
- Tests: AppLaunchTest (Robolectric) launches the app, walks onboarding, opens every main screen, and upgrades a v0.2 database. 20 tests total.

## v0.9.4
- Removed the Termux-app bridge (commands/MCP run in the built-in Linux or Android shell) and the on-phone APK build kit.
- Installed tools first: the agent checks the user's Tools list and Linux PATH (installed_tools) before running or installing anything, and install_tool refuses to reinstall an existing tool.
- UI: richer welcome hero, 2×2 suggestion grid, focused composer ring and primary send button, model pill and drawer header polish.

## v0.9.5 — Clozed Code features port (ideas only; clean-room implementation)
- `patch`: validated multi-file diff (`*** Add/Update/Delete File`, `@@` hunks, optional `Move to`); nothing is written unless every hunk matches the current file text.
- `diagnostics`: quick JSON/XML validity and bracket/string balance check for common source languages.
- `ask_user`: the agent pauses and asks the user a question with suggested options before continuing; the answer is echoed into the chat.
- Updated SystemPrompt: prefer patch for multi-file edits, run diagnostics after edits, ask_user when a decision is needed.
- MCP OAuth: remote MCP servers that use OAuth can be signed in from the server screen (authorization code + PKCE, dynamic client registration, tokens in the encrypted vault).
- Bundled `reverse-engineer-anything` skill (adapted from rea, MIT): REA MCP tools when available, `npx rea-agents` otherwise, or JADX/apktool inside Linux.

## v0.9.6 — Motion graphics skills + a skills connector
- New "Motion graphics" kit (Linux screen): ffmpeg + Python 3, Node 22 + Playwright/Chromium, and the motion-graphics skills (Barty-Bart/motion-graphics, MIT) cloned into Linux so their scripts render clips.
- "Install skills" now features `Barty-Bart/motion-graphics` for one-tap install; paste any `owner/repo` or skills.sh link to add Claude-Code-style skills.
- Larger skill repos copy fully (GitHub skill download raised to 300 files / 12 MB).

## v0.6.0
- Builds: `./gradlew :app:assembleFullDebug` (GitHub release, everything) and `:app:assemblePlayDebug` / `:app:bundlePlayRelease` (Google Play). See LICENSE_AUDIT.md for why.
- Linux: Back up / Restore / Reset a distro. Android commands inside Linux: agentmt-open, agentmt-share, agentmt-clipboard-get, agentmt-clipboard-set, agentmt-notify, agentmt-toast.
- Settings → Open source licenses. native/termux-jni holds the 16 KB-aligned libtermux.so build.

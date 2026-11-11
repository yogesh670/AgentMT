# AgentMT

A mobile-first AI coding agent for Android. Kotlin, Jetpack Compose, Material 3.

## What it does

AgentMT runs on your phone and uses LLMs (OpenAI-compatible or Anthropic) to help you build and change software in your project. It combines a **tool-use agent loop** with Android UI, so you can explore code, edit files, run commands, render clips, and more — entirely on-device.

### Core features

- **Chat with streaming replies** — Markdown output, copyable code blocks, model pill in the header.
- **Real agent loop with tools** — Built-in tools: `list_dir`, `read_file`, `write_file`, `edit_file`, `patch` (validated multi-file diff), `glob`, `grep`, `run_command`, `web_fetch`, `load_skill`, `diagnostics` (JSON/XML/balance check), `ask_user` (pause for a user decision), plus remote **MCP** tools.
- **Approval cards** — Every tool call that modifies state shows a diff preview; choose **Allow once / Allow always / Deny**. Permission rules persist and can be edited.
- **Projects & folders** — Create a project inside the app, or open any phone folder (All-files-access picker). Project files live inside the built-in Linux environment (proot/Alpine) and are visible from the Terminal and File browser.
- **Runs & timeline** — Each run shows a timeline of steps, token usage, changed files, and a **Stop** button. History lists all runs with dates, titles, and status (running/finished/failed).
- **Providers & encrypted keys** — OpenAI-compatible or Anthropic APIs. Keys are stored in the encrypted SecretVault. "Test & fetch models" button validates the key and populates the model pill.
- **Skills (SKILL.md)** — Built-in per-user and per-project skills. Skills define which tools they need and expose a description; the agent loads them automatically. Install from GitHub owner/repo or paste a `skills.sh` link.
- **Remote MCP servers** — OAuth (authorization-code + PKCE, dynamic client registration) or a manually entered Bearer token. Servers surface their tools as if they were built-in.
- **Foreground service** — Keeps runs alive in the background; the UI stays in sync when you switch apps.

### Linux environment & kits

The agent works inside a **built-in Linux rootfs** (Alpine via proot). Commands run as root; packages are installed with `apt/apk` or `pip`/`npm`. The app ships four **one-tap kits** that install everything in a single job:

| Kit | Installs | Approx. size |
|-----|----------|--------------|
| **Reverse engineering** | apktool, jadx 1.5.6, radare2, Java 17, Node 22, REA agents | ~900 MB–1.2 GB |
| **Web dev** | Node.js 22, npm, Python 3, git, curl | ~300 MB |
| **Media** | ffmpeg, yt-dlp (pip) | ~250 MB |
| **Motion graphics** | ffmpeg, Python 3, Node 22 + Playwright/Chromium, motion-graphics skills (Barty-Bart/motion-graphics, MIT) | ~700 MB–1 GB (Chromium is the bulk) |

Kits install into `/opt/tools/` inside Linux. Use the **Linux & tools** drawer entry to install or remove a kit. You can also add individual Linux packages, Python tools, or GitHub repos.

### Terminal

- Shell: `/system/bin/sh` with toybox utilities; `run_command` executes inside Linux.
- Distro install: Alpine via proot (download size ~150 MB). `apk add` packages are available.
- Guest cwd: paths inside the rootfs are translated; **no host `/` is bound** into the proot environment.
- Tools already installed appear in the Linux screen; remove ones you no longer need to reclaim space.

### Browser & extensions

- **WebView-based browser** that shares the user's Android logins. Useful for sign-ins and captchas; for those, open the native Browser app from the menu.
- Chrome **MV3 extensions cannot run** in Android's WebView. AgentMT's "Extensions" feature accepts **Tampermonkey userscripts only** — paste the script URL or code and it will be evaluated in the WebView.
- Remote MCP servers can expose HTTP APIs that the browser controls.

### Tasks & runs

- **New task** from the composer or drawer. Define a title, optional instructions, and a todo list. The agent tracks progress with `update_todos` and reports a final summary.
- Runs can be **restarted** (keeps finished steps) or **continued** (sends CONTINUE_TEXT). Unanswered tool calls are dropped by `buildHistory`.

### Settings

- **Licenses** — Open-source license list (MIT, Apache-2.0, GPL, etc.) with attribution for bundled skills and libraries.
- **Rules** — Approval-mode configuration (once/always/deny per tool category) and a diff-preview editor for rule changes.
- **Providers** — Add/edit OpenAI/Anthropic keys, test models, view provider catalog.
- **Onboarding** — 5-step setup: permission approval rows (all files, notifications, background/battery, install apps), then launch.

## Build & development

- Open in Android Studio (Ladybug or newer) and run, or from the command line:
  ```
  ./gradlew :app:assembleFullDebug
  ./gradlew :app:assemblePlayDebug
  ./gradlew :app:testFullDebugUnitTest
  ./gradlew :app:testPlayDebugUnitTest
  ```
- **JDK 17**, `compileSdk 36`, `minSdk 26`, `targetSdk 36`.
- See `DECISIONS.md` for architectural choices (why Linux proot, why MV3 is avoided, why kits are opt-in, etc.).
- See `LICENSE_AUDIT.md` for the release splitting logic and native-library notes.

## Layout (key packages)

```
app/            AgentMTApp (drawer + navigation), AppContainer (DI), theme
agent/          AgentEngine, PermissionEngine, SystemPrompt, AgentService
agent/llm       OpenAiClient, AnthropicClient, SSE helpers
agent/tools     Workspace + file/shell/web/skill/MCP tools
agent/mcp       Streamable HTTP MCP client
data/           Room DB, Keystore vault, settings
feature/        chat, files, runs, tools, providers, settings, onboarding, projects
branding/       icon master (SVG/PNG), Play Store icon 512, onboarding art
native/         termux-jni: 16 KB page-aligned libtermux.so rebuild (see native/termux-jni/README.md)
```

## Storage note

Each kit installs its tools **inside the Linux environment only**; they do not affect your personal photos, downloads, or other Android apps. Typical sizes:

- Reverse engineering: ~900 MB–1.2 GB (JDK + apktool + radare2 + jadx + Node + npm)
- Web dev: ~300 MB (Node, Python, git)
- Media: ~250 MB (ffmpeg, yt-dlp)
- Motion graphics: ~700 MB–1 GB (Chromium download is the main component)

You can remove a kit or individual tools from the **Linux & tools** screen to free space.

## Version history (abbreviated)

- **v0.9.6** — Motion graphics kit, GitHubFetcher 300-file/12 MB cap, one-tap install of Barty-Bart/motion-graphics, Android Play releases.
- **v0.9.5** — `patch`, `diagnostics`, `ask_user` tools; MCP OAuth; Clozed Code features port (clean-room).
- **v0.9.4** — Removed Termux-app bridge; installed-tools-first flow; richer welcome UI.
- **v0.4.0** — Terminal (Alpine proot), Plan toggle, build variants, 20 AppLaunchTest tests.

## License

See LICENSE_AUDIT.md for the project licensing details (the motion-graphics skill is MIT; the AgentMT codebase is distributed under the project's chosen license).
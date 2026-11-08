# License & Google Play audit (v0.6.0, 2026-11-07)

Every repo you shared, its license, and how AgentMT uses it. "Idea only" = no code copied, so its license puts no duty on AgentMT.

| Repo | License | How AgentMT uses it | Duty |
|---|---|---|---|
| termux/termux-app | GPL-3.0 only, with Apache-2.0 exceptions for terminal-view and terminal-emulator | Only terminal-view + terminal-emulator v0.118.1 (Apache-2.0) via JitPack | Keep the Apache notice (done in Open source licenses screen). Do NOT copy anything else from termux-app (GPL-3.0). |
| termux/termux-packages | Each package under its own license (build scripts follow the package) | We ship Termux's proot and libtalloc builds (full build only) | proot GPL-2.0 + talloc: include license texts, source links, and a source offer (done). |
| termux/proot (via termux-packages) | GPL-2.0 | libproot.so, libproot-loader.so (full build only), runs as a separate program | Same as above. Mere aggregation: AgentMT itself does not become GPL. |
| termux/termux-tools | GPL-3.0 | Idea only: agentmt-open / -share / -clipboard / -notify / -toast written from scratch | None. Do not copy its scripts. |
| termux/proot-distro | GPL-3.0 | Idea only: distro backup / restore / reset written from scratch (TarWriter, LinuxEnv.backup/restore) | None. Do not copy its code. |
| santoshkurmi/AntiGem | GPL-3.0 | Not used | If code is ever copied, the whole app must become GPL-3.0. |
| anomalyco/opencode | MIT | Idea only (Build/Plan modes) | None (keep credit if code is ever copied). |
| anthropics/claude-code | Proprietary (Anthropic Commercial Terms) | Plugin folder layout (format) only | Never bundle its code, prompts or binaries. |
| anthropics/claude-plugins-official | Apache-2.0 | Users may install plugins at runtime | Not bundled. |
| anthropics/skills | Mixed: most skills Apache-2.0; docx/pdf/pptx/xlsx skills are source-available, not open source | Users may install at runtime | Never bundle the document skills. |
| google-ai-edge/gallery | Apache-2.0 | Idea only (on-device models planned) | Keep notice if code is used. |
| google/smali | BSD-style (see its NOTICE) | Not bundled | Keep notice if bundled. |
| iBotPeaches/Apktool | Apache-2.0 | Not bundled | Keep notice if bundled. |
| AbdurazaaqMohammed/AntiSplit-M | Apache-2.0 | Not bundled | Keep notice if bundled. |
| morluto/rea | MIT | Installable at runtime as a GitHub tool | Not bundled. |
| usestrix/strix | Apache-2.0 | Not used | — |
| yt-dlp/yt-dlp | Unlicense (public domain) | Installable at runtime via pip | Not bundled. Play may still reject apps that download from YouTube against its ToS. |
| vadimdemedes/ink | MIT | Not used | — |
| cheaper-inference/OmniRoute, decolua/9router, tashfeenahmed/freellmapi | MIT | Idea only (routing profiles) | None. |
| Leonxlnx/taste-skill, Nutlope/hallmark, VoltAgent/awesome-design-md, bangtutorial/bang-motion, nextlevelbuilder/ui-ux-pro-max-skill, filiksyos/gitreverse | MIT | Installable at runtime as skills | Not bundled. |
| gauravghongde/social-icons | No license | Not used | Can't be used legally; do not bundle. |
| github.com/antigravity | Org, no single license | Not used | — |
| lzhiyong/android-sdk-tools | Apache-2.0 | aapt2 downloaded at runtime by the APK build kit | Not bundled. |

Bundled skills in assets/skills (code-review, port-from-project, skill-creator, spike, systematic-debugging) were first modelled on the ClozedCode APK. Before Play release, confirm the wording is our own (or rewrite) since ClozedCode is not open source.

## Google Play blockers and how v0.6.0 handles them

Play's Device and Network Abuse policy: an app "may not download executable code (such as dex, JAR, .so files) from a source other than Google Play". Downloading Ubuntu/Alpine, apt/pip packages, JDK and aapt2 is exactly that; it is why Termux itself left Google Play.

So the project now has two builds:
- **full** (GitHub releases / F-Droid style, like Termux): everything — Linux, APK build kit, tools manager, All files access, battery exemption request.
- **play** (Google Play): no proot binaries, no Linux downloads, no MANAGE_EXTERNAL_STORAGE, no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. Projects live inside the app. Browser, userscripts (JavaScript in a WebView is allowed), skills, MCP, chat all stay.

- 16 KB pages: the terminal-emulator AAR ships a 4 KB-aligned libtermux.so (Play rejects this for targetSdk 35+). v0.6.0 ships our own 16 KB-aligned rebuild (native/termux-jni). proot/talloc are already 16 KB aligned.

Still to do in Play Console for the play build: foreground service (dataSync) declaration with a short video, Data safety form, privacy policy URL, AAB upload (bundlePlayRelease) with a release keystore.

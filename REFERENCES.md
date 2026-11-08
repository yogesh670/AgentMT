# Reference review (v0.3.0, 2026-11-06)

Each listed repo was judged for relevance, licence, Android feasibility and security. Nothing was copied; patterns were re-implemented.

| Reference | Verdict | What AgentMT took / why not |
|---|---|---|
| tashfeenahmed/freellmapi, cheaper-inference/OmniRoute, decolua/9router | **Used (patterns)** | Per-key cooldown on 429, Retry-After/X-RateLimit-Reset parsing, ordered fallback profiles, health + usage ledger, OpenAI-compatible gateway as a provider. Implemented in `agent/routing` per doc 22. No account rotation to evade quotas (doc 22 non-goal). Any of these gateways can be added as a Custom provider. |
| santoshkurmi/AntiGem | Not integrated | Gateway-style project; same role covered by "custom OpenAI-compatible endpoint". |
| anthropics/skills, skills.sh (vercel-labs/skills) | **Used** | SKILL.md format; install from GitHub/skills.sh links, enable/disable, project/global scope, supporting files via `load_skill(file=…)`. Scripts are never executed. |
| anthropics/claude-plugins-official | **Used (format)** | Plugin installer reads `.claude-plugin/plugin.json`, `marketplace.json`, `skills/`, `commands/`, `.mcp.json` (HTTP servers). Hooks, agents, scripts, stdio MCP are listed as unsupported and never run. |
| anthropics/claude-code | Used (UX patterns) | Slash commands with `$ARGUMENTS`, todo checklist tool, approvals. |
| google-ai-edge/gallery (Apache-2.0) | **Planned, next round** | Uses LiteRT / LiteRT-LM. On-device inference needs the LiteRT-LM AAR (large native libs) and 0.5–4 GB model files; tool calling is limited. v0.3 supports local models through Ollama / LM Studio / llama.cpp servers with auto-detection. |
| morluto/rea | Not integrable | Node 22 + JADX desktop MCP server; cannot run inside the app. Can be connected as a remote MCP server if hosted elsewhere. |
| iBotPeaches/Apktool, google/smali, AbdurazaaqMohammed/AntiSplit-M | Planned (APK inspector) | Java tools; a read-only APK inspector (manifest, permissions, dex class list) is feasible next round. Rebuilding/signing APKs on-device is out of scope. |
| yt-dlp/yt-dlp | Not feasible natively | Python program; Android has no Python runtime in-app. Users can run it in Termux; AgentMT can drive files it produces. |
| usestrix/strix | Not integrated | Docker-based security agent; needs a desktop/server. |
| filiksyos/gitreverse | Pattern only | "Analyze a GitHub repo" task template uses the GitHub API + web_fetch. |
| vadimdemedes/ink | Not relevant | React for terminal UIs (Node). |
| VoltAgent/awesome-design-md, nextlevelbuilder/ui-ux-pro-max-skill, Leonxlnx/taste-skill, Nutlope/hallmark, bangtutorial/bang-motion | Used as design guidance / installable skills | Informed the flatter, mono-accented UI (no purple gradients/glass). The skill repos can be installed in-app from the Skills tab. |
| gauravghongde/social-icons | Not needed | App uses Material Symbols; no social login. |
| github.com/antigravity | Not integrated | Org page, no specific component to adopt. |

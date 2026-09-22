# Agentisco

A full AI coding agent, developer workspace, and code editor running entirely on Android. Agentisco ships a Debian Linux userland inside the app via `proot` (no root required), drives it with an LLM agent that has 21 real tools, and gives you an IDE-grade editor and Git client over the results — all offline-capable except for the LLM calls themselves.

- **Package:** `com.agentisco` · **Current version:** 1.0 (versionCode 1; CI assigns release versions from git tags)
- **Platforms:** Android 7.0+ (minSdk 24), `arm64-v8a` and `armeabi-v7a`
- **UI:** Jetpack Compose, dark-only custom theme

---

## Feature overview

| Surface | What it does |
|---|---|
| **Agent** | Multi-turn chat with streaming text/reasoning, inline tool cards, approval gates, per-project sessions persisted in Room, mid-conversation model switching, per-call tool cancellation, failed-turn resume |
| **Terminal** | Interactive Debian shell in a vendored Termux terminal emulator (PTY via JNI), plus a dev key strip; project folders bind-mounted at `/workspace` |
| **Editor** | Tabs, syntax highlighting for 26 languages, 5 themes, symbol outline, find & replace, go-to-line, undo/redo, word wrap, bracket matching, "Ask Agent" with file context |
| **Projects** | Real disk-measured metadata (size, last-modified, type from marker files, extracted app icon), workspace storage meter, import/create with zip support, one-way sync back to a source folder |
| **Git** | Real `git` executed inside proot: status, diffs, stage, revert, commit (with AI-generated commit messages), history |
| **Files / Diff / Build-Run** | File tree browser, diff viewer, script runner (`npm run …` style build/test) |
| **Updates** | In-app self-update from GitHub Releases with resumable download and multi-layer APK verification |

Navigation has no Jetpack Navigation: an `AppDestination` enum is held in a `StateFlow` and screens are swapped with a `Crossfade`, under a global project/Git header + IDE toolbar (`AgentIDETopAppBar`) and a three-tab bottom bar (`AgentIDEBottomBar`). A command palette (`>`) reaches the remaining destinations and can launch agent tasks directly.

---

## Architecture

```
app/src/main/java/com/agentisco/
├── MainActivity.kt              # Entry point; single Activity, edge-to-edge Compose host
├── agent/                       # AI agent subsystem
│   ├── runtime/AgentRuntime.kt  # Agentic loop (plan → stream → tools → repeat)
│   ├── llm/                     # Provider clients + dispatch (LlmService)
│   ├── tool/                    # 21 tool definitions + AgentToolRegistry
│   └── permission/              # DestructiveCommandGuard, AgentPermissions
├── core/model/                  # AppDestination
├── data/
│   ├── local/                   # Room chat DB, project registry, provider config stores
│   ├── repository/              # WorkspaceRepository, AgentChatStore, UpdateRepository
│   └── model/                   # Project, ProjectKind, LLM models, …
├── editor/                      # Headless editor engine (syntax, symbols, models)
├── settings/                    # UserPreferences (updates auto-check, …)
├── ui/                          # Screens, components, theme, editor UI, view models
└── workspace/
    ├── terminal/                # Debian bootstrap, proot argv, PTY process managers
    ├── git/                     # GitRepositoryManager, DiffEngine
    └── filesystem/              # ProjectFileSystem, ProjectMetadataScanner, WorkspaceStorage
```

`WorkspaceRepository` is the composition root: it owns navigation state, the project list, the agent runtime, terminal sessions, git, and provider config, and exposes everything as `StateFlow`s consumed by `WorkspaceViewModel`.

### The Linux workspace

- A Debian rootfs (Ubuntu 24.04 base per ABI, pinned SHA-256) is **shipped inside the APK** as `jniLibs` (`librootfs64.so` / `librootfs32.so`) — nothing is downloaded. On first launch `DebianBootstrap` verifies the digest and extracts the gzip tar to `filesDir/linux-rootfs`, walking a `NotBootstrapped → Verifying → Extracting → Configuring → Ready` state machine.
- `ProotArgsBuilder` constructs the `proot` argv: `--kill-on-exit --link2symlink -0 -r <rootfs>` with binds for `/dev`, `/proc`, `/sys`, `/sdcard`, and the open project at the fixed guest path `/workspace`. `-0` fakes root so `apt` works (proot-distro style); `PROOT_NO_SECCOMP=1` is set for device compatibility.
- Projects live **inside the rootfs** at `/root/projects` (host: `filesDir/linux-rootfs/root/projects`). `guestPathFor()` maps host ↔ guest paths. Import copies folders in (zip imports are zip-slip-guarded) and optionally mirrors changes back to the original `sourcePath`.
- Interactive terminal sessions and agent `run_command` share this environment; git operations execute the real `git` binary inside it.

### Agent runtime

`AgentRuntime.executeTask()` runs the loop:

1. Build a system prompt containing the workspace file tree (depth 3, capped).
2. Inject the persisted conversation history and stream the next LLM turn.
3. If the model requests tool calls: parse/validate arguments, run the permission check, execute (up to 4 tool calls concurrently via a `Semaphore`), append tool results as messages, and loop — until final text or the iteration cap.

Streaming is a sealed `AgentStreamEvent` flow (`Token`, `ReasoningToken`, `ToolStarted/Finished`, `ApprovalRequested/Resolved`, `TextReset`, `ToolCancelled`, …) consumed by the repository and serialized to the chat database through a single-writer `Channel` in `AgentChatStore`, so out-of-order writes can't corrupt a transcript. Transient LLM failures retry up to 5 times with backoff; a running turn can be cancelled per tool call (SIGKILL into the PTY) and a failed turn resumed.

### LLM providers

`LlmService` dispatches on a `LLMProtocol` enum to four streaming clients:

| Protocol | Endpoint | Notes |
|---|---|---|
| `OpenAIChatCompletionsClient` | `/chat/completions` | Bearer auth |
| `OpenAIResponsesClient` | `/responses` | Stateless, `store:false`, ≥8192 output tokens |
| `AnthropicMessagesClient` | `/v1/messages` | `x-api-key`, extended thinking intentionally off |
| `GeminiInteractionsClient` | `/v1beta/interactions` | **Stateful** server-side chains via `previous_interaction_id`, persisted in `interactions.json` with auto-reopen on rejected chains |

Providers and models are configured in-app and stored in `providers.json`; **API keys live in a separate `credentials.json`** in app-private storage. "Test connection" probes `/models` for free before falling back to a minimal completion. Errors are classified (`AUTH`, `RATE_LIMIT`, `SERVER`, `NETWORK`, `TIMEOUT`, …) — rate limits and 5xx are retried, invalid-request errors are surfaced immediately. The model dropdown stays usable during a running turn so a rate-limited model can be swapped mid-conversation.

### Agent tools (21)

- **Filesystem:** `list_files`, `read_file` (32k cap), `read_files` (batch), `write_file`, `create_file`, `edit_file` (exact-string replace, uniqueness enforced), `delete_file` (always approval-gated), `move_file`, `file_info`, `directory_tree`
- **Search:** `search_files`, `regex_search`, `glob_files`
- **Execution:** `run_command`, `interrupt_terminal`, `build`, `test`, `task_plan` (visible checklist)
- **Git:** `git_status`, `git_diff`, `git_stage`, `git_commit`

Safety: `DestructiveCommandGuard` flags patterns (`rm -rf`, `git reset --hard`, force-push, `dd`/`mkfs`, fork bombs, `DROP TABLE`) and user permission modes range from always-ask to allow-all, with file-modification and command policies configured independently in Settings.

### Editor

The engine (`editor/`) is headless and unit-tested; the UI (`ui/editor/`) wires it into Compose.

- `SyntaxHighlighter`: line-based tokenizer producing per-line `AnnotatedString`s memoized in a concurrent cache for 60fps typing; handles multi-line block comments and Python docstrings. 26 languages via `Language.kt`; 5 themes (Agentisco Dark, GitHub Dark, Monokai Pro, One Dark Pro, Tokyo Night).
- `SymbolExtractor`: regex-based outline (classes, functions, enums, headings…) backing the symbols sheet and go-to-line.
- `EditorModels`: tabs with full-content undo/redo stacks (cap 200), dirty tracking, auto-save/format-on-save settings, find & replace with match-case/whole-word.

### Data layer

- **Room** (`agentisco_chat.db`, version 5): `project → session → message → block` with FK cascades; blocks carry `kind = text | tool | approval`, exit codes and call IDs. Migrations are non-destructive and incremental (1→5), including a snapshot-rebuild at 4→5 to preserve cascaded children.
- **Project registry** (`projects.json` + `last_project.txt`) caches per-project config; the authoritative config is `.agentisco.json` inside each project root.
- `ProjectMetadataScanner` measures real size / newest mtime / icon / `ProjectKind` (from marker files such as `build.gradle`, `package.json`, `Cargo.toml`, …) off the main thread, with a TTL + root-mtime cache and hard walk bounds (depth 32, 200k entries) so a pathological tree can't hang a refresh.

### Self-update

`UpdateRepository` checks `api.github.com/repos/gamistudios/Agentisco/releases/latest`, compares `versionCode` parsed from the tag, and downloads with HTTP-Range resume (5 attempts, linear backoff) with progress measured against GitHub's reported asset size. A downloaded APK is only trusted after: exact byte-size match, SHA-256 `assetDigest` check when available, ZIP magic validation, and package-name verification via `PackageArchiveInfo`; a completion marker gates reuse of partial downloads. Install launches the system installer on a `FileProvider` URI.

---

## Tech stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose (BOM 2024.09.00), Material 3, custom dark theme |
| Build | AGP 9.1.1, Gradle version catalog (`gradle/libs.versions.toml`), KSP 2.3.12, configuration cache |
| Persistence | Room 2.7.0; JSON stores in app-private files |
| Networking | OkHttp 4.10, Retrofit 2.12, kotlinx-serialization-json 1.7.3 |
| Async | kotlinx-coroutines 1.10.2, StateFlow-driven UI |
| Terminal | Termux terminal-emulator/view vendored under `com/termux/` + JNI PTY (`app/src/main/cpp/termux.c` → `libtermux.so`) |
| Linux | proot + Debian/Ubuntu rootfs shipped in jniLibs; commons-compress 1.26.2 + xz for extraction |
| Native | CMake 3.22.1, NDK 27.2.12479018 |
| Testing | JUnit, Robolectric 4.16.1, Roborazzi 1.59.0 (screenshot tests) |

## Notable technical decisions

- **targetSdk is pinned to 28 deliberately.** Android 10+ (API 29) SELinux policy blocks `execve` of binaries in app-writable storage, which would kill proot/Termux execution. Termux itself applies the same pin. The `ExpiredTargetSdkVersion` lint is disabled accordingly.
- **Rootfs ships in the APK** as jniLibs so the workspace works with no network and no download step; digests are pinned for integrity.
- **Real git, real Linux** — no simulated shell or JGit-only paths for execution; the agent and the UI operate on the same environment.
- **Dark-only, custom theme** with no dynamic color, tuned for the IDE look (Electric Blue accent system).
- **Release builds currently skip minification** (`isMinifyEnabled = false`); proguard files exist but are inactive.

## Building

Prerequisites: JDK 21, Android SDK (platform 36), NDK 27.2.12479018 + CMake 3.22.1 (Gradle can fetch via `sdkmanager`).

```bash
# Debug APK
./gradlew :app:assembleDebug

# Unit tests (incl. Robolectric screenshot tests via Roborazzi)
./gradlew :app:testDebugUnitTest

# Release build — requires keystore env/`.env` entries:
#   AGENTISCO_KEYSTORE_PATH / _PASSWORD / _KEY_ALIAS / _KEY_PASSWORD
# See KEYSTORE_SETUP.md and generate-keystore.sh.
./gradlew :app:assembleRelease
```

`debug.keystore` is checked in so debug signing works out of the box. Local SDK path goes in `local.properties` (`sdk.dir`).

### CI/CD

`.github/workflows/ci.yml`:
- **validate** — on pushes/PRs: `assembleDebug` + `testDebugUnitTest` on JDK 21 with pinned SDK/NDK.
- **release** — manual dispatch or a commit message matching `release` / `release vX.Y.Z`: auto-increments the git tag, rewrites `versionCode` as `major*10000 + minor*100 + patch`, and publishes signed APK + AAB to GitHub Releases. The in-app updater consumes these releases.

## Security notes

- API keys are stored separately from provider config in app-private storage and never committed; the release keystore and `GITHUB_SECRETS.txt` must stay out of version control — CI credentials belong in GitHub Actions secrets.
- The agent's destructive-command guard plus per-mode approval policies are the safety boundary for autonomous file and shell operations; `delete_file` always requires explicit approval.

# Developer

Gemechis Chala

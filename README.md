# CodeGuard AI

CodeGuard AI is a privacy-first, on-device Java security and quality analyzer for Snapdragon-ready developer workflows. It combines deterministic static analysis with an AI explanation and fix recommendation layer, while requiring explicit approval before any code is changed.

## Project goals

- Detect Java security and quality issues with deterministic analysis
- Explain findings in context with an AI provider abstraction
- Recommend a single fix and show a diff before approval
- Require explicit developer approval before applying a fix
- Rerun analysis after changes and compare old/new findings
- Stay privacy-preserving and model-agnostic for Qualcomm deployment

## Architecture

CodeGuard AI follows a layered architecture:

- Java 21 + Spring Boot 3 backend
- React + Vite frontend
- Static analyzer layer: PMD / SpotBugs / Checkstyle-inspired determinism
- AI provider abstraction: `AiInferenceProvider`
- Qualcomm deployment layer: `backend/src/main/java/com/codeguardai/ai/qualcomm`

### Repository layout

```text
backend/                 Spring Boot API, static analysis, AI provider, Qualcomm layer
  src/main/java/com/codeguardai/service/   PMD, SpotBugs, Checkstyle, SafeFixApplier
  src/main/java/com/codeguardai/ai/       inference provider abstraction + qualcomm/
  src/test/java/                          unit and Spring context tests
frontend/                React + Vite + TypeScript dashboard (Monaco bundled locally)
  src/api/                                backend contract types
  src/ux/                                 pure state derivations (runtime status, AI state)
  src/components/                         dashboard components
docs/                    DEMO.md, QUALCOMM-GENIEX.md runbook, evidence/ rules
scripts/                 fetch and start the real local runtime, GenieX verification
models/                  downloaded GGUF model (git-ignored, ~1.04 GB)
tools/                   downloaded llama.cpp build (git-ignored, ~65 MB)
```

### Documentation

| Document | Purpose |
| --- | --- |
| [`docs/DEMO.md`](docs/DEMO.md) | End-to-end runnable demo: fetch the runtime and model, start the local server, backend and frontend, then walk through scan, analyze, propose, apply, verify |
| [`docs/QUALCOMM-GENIEX.md`](docs/QUALCOMM-GENIEX.md) | Qualcomm GenieX NPU verification runbook: the six evidence gates, verification modes, tiers, reason codes, local Snapdragon and Qualcomm Device Cloud runs, troubleshooting |
| [`docs/evidence/README.md`](docs/evidence/README.md) | Evidence artifact shape and the rules for producing and keeping it |

## Development environment

- Java 21
- Spring Boot 3.x
- Maven
- React + Vite + TypeScript
- Local development machine used for coding, validation, and demonstration
- `monaco-editor` is bundled locally (see `frontend/src/monacoSetup.ts`), so the editor never loads code from a public CDN

## Snapdragon deployment environment

The project is designed to support Snapdragon-powered HP PCs and Qualcomm AI/GenieX/QAIRT runtime integration. The Qualcomm adapter lives in `backend/src/main/java/com/codeguardai/ai/qualcomm` and is intentionally isolated from the core app logic so a real Qualcomm execution environment can be added without redesigning the application.

**NPU execution is NOT verified on the current machine.** The host used for
development, validation and demonstration is Intel/Windows x86-64, and
`SystemQualcommRuntimeDetector` observes that no QNN/QAIRT/SNPE runtime
components are present on it, so `GET /api/runtime` reports
`qualcommNpuVerified: false`. No Snapdragon or Hexagon execution has been measured
here, and this project does not claim otherwise. Any Qualcomm execution is an
explicit deployment-time integration point that must be proven with real
evidence on Snapdragon hardware; see [NPU execution status](#npu-execution-status)
and [`docs/QUALCOMM-GENIEX.md`](docs/QUALCOMM-GENIEX.md).

## AI model and runtime

- Model identifier: configurable via `application.yml`
- Runtime: configurable via `AiInferenceProvider` and Qualcomm adapter configuration
- Context length: configurable
- Timeout: configurable
- Temperature / generation settings: configurable
- No hard-coded credentials or API keys

### Local inference runtime (llama.cpp)

AI inference runs against a local, OpenAI-compatible `llama-server` process. No remote model provider is configured, so analysed source code never leaves the machine.

The complete setup, including the pinned runtime and model, is documented in [`docs/DEMO.md`](docs/DEMO.md). In short:

```bash
# 1. download the pinned llama.cpp runtime (checksum verified)
./scripts/fetch-llama.ps1

# 2. download the pinned demo model (~1.04 GB, not committed to the repo)
./scripts/fetch-model.ps1

# 3. start the real local inference server
./scripts/start-llama.ps1
```

The documented demo model is **Qwen2.5-Coder-1.5B-Instruct, Q4_K_M GGUF**, served from `models/`. Neither the runtime nor the model is downloaded automatically at application startup; both are fetched only when these scripts are run explicitly.

To confirm the OpenAI-compatible endpoint responds:

```bash
curl http://127.0.0.1:8081/v1/models
```

Configuration lives in `backend/src/main/resources/application.yml` under `codeguard.ai` (`provider`, `base-url`, `model`, `runtime`, `timeout-ms`, `context-length`, `temperature`, `health-check-enabled`). The configured `model` value must match the model identifier reported by `llama-server`; `start-llama.ps1` passes `--alias` so the reported identifier is the model filename.

`AiProperties.validateLoopbackUrl` rejects every non-loopback host, so the backend cannot be pointed at a remote or cloud endpoint. `HttpLocalAiInferenceProvider` is the primary `AiInferenceProvider`; if `llama-server` is unreachable or returns malformed output, it retries once with a strict JSON-only prompt and then returns an explicitly `uncalibrated` degraded explanation instead of failing the scan or fabricating a confidence score.

`GET /api/runtime` reports verified AI provider status: `aiConfigured` (configuration present), `aiProviderReachable` (a real request to the loopback provider succeeded) and `aiModelServed` (the provider reports the configured model). These come from an actual `GET /v1/models` call, so availability is never inferred from configuration alone.

## NPU execution status

NPU execution is not claimed on this Intel Windows x86-64 development machine, and configuration is never treated as evidence: `codeguard.qualcomm.npu-execution-verified` states intent only. `GET /api/runtime` reports the host facts (operating system, CPU architecture, detected architecture, configured deployment mode, configured execution target, whether QNN/QAIRT components exist) plus the verification verdict:

- `qualcommNpuVerified` - true only when the evidence gates passed, never from configuration.
- `qualcommVerificationTier` - `npu-verified`, `server-reachable`, `no-evidence`, `configuration-only` or `disabled`.
- `qualcommVerificationReasons` - one reason code per gate, for example `artifact-missing` or `http-artifact-mismatch`.
- `qualcommVerificationEvidence` - the GenieX server round trip, the benchmark artifact and its raw statistics.

To prove NPU execution, a live GenieX server round trip and a `geniex-bench` artifact that records the NPU compute unit must both exist and agree on the model that was run. `docs/QUALCOMM-GENIEX.md` is the full runbook (local Snapdragon device and Qualcomm Device Cloud session), and `scripts/verify-geniex-npu.ps1` captures the evidence.

## Static analysis

`StaticAnalysisService` runs the real analyzers and normalizes their output into
one `Finding` shape:

- **PMD** (`PmdAnalyzer`) - runs the PMD Java engine on the submitted source.
- **SpotBugs** (`SpotBugsAnalyzer`) - runs the SpotBugs engine.
- **Checkstyle** (`CheckstyleAnalyzer`) - runs the Checkstyle engine.
- **CodeGuard-Heuristics** - CodeGuard's own token based checks. They are
  **disabled by default** (`codeguard.heuristics.enabled=false`) and are reported
  under the separate `CodeGuard-Heuristics` analyzer name, never attributed to
  PMD, Checkstyle or SpotBugs, so a normal scan only contains genuine third-party
  findings.

Analyzer attribution is preserved end to end: the static analyzer owns the
finding metadata (rule, severity, line, snippet), while the AI layer only
explains it. If an analyzer fails on a snippet the scan still completes and the
failure is reported instead of being hidden or replaced by a guessed result.

## Features

1. Dashboard with scan totals and finding breakdown
2. Java editor with sample code loading and file upload support
3. Static analysis pipeline normalizing PMD, SpotBugs, and Checkstyle-like results
4. AI explanation and fix recommendation with structured JSON response
5. Diff review and explicit approval before fix application
6. Verification step comparing old and new findings
7. Scan history support via in-memory persistence, without requiring Postgres
8. Educational sample vulnerabilities for demonstration

## Sample vulnerabilities included

- NullPointerException risk
- SQL injection
- Resource leak
- Hardcoded credentials
- equals/hashCode contract issue
- Swallowed exception

## API contract

- `POST /api/scans`
- `GET /api/scans/{id}`
- `GET /api/scans/{id}/findings`
- `POST /api/findings/{id}/analyze`
- `POST /api/findings/{id}/propose-fix`
- `POST /api/findings/{id}/apply-fix`
- `POST /api/scans/{id}/verify`
- `GET /api/health`
- `GET /api/runtime`

### SafeFix: applying a fix

`POST /api/findings/{id}/apply-fix` is handled by `SafeFixApplier`
(`backend/src/main/java/com/codeguardai/service/SafeFixApplier.java`) and is line
scoped. The model proposed correction is only written to the line range proven by
the finding metadata (`line` plus `codeSnippet`) and every unrelated source line
is preserved byte for byte. The stored file is never replaced by a model
generated snippet.

When that range cannot be confirmed - missing, invalid or out of range line, missing snippet, or a source that no longer matches the finding snippet - the stored source is left untouched and the request answers `409 Conflict` with `status: "requires-review"`, the target line range and `sourceModified: false`, so a developer applies the change manually. Repeating a correction that is already present is reported as `already-applied` and writes nothing.

## Running locally

### Backend

```bash
cd backend
mvn spring-boot:run
```

Requires Java 21 on the `PATH` (`java -version` must report 21) and Maven 3.9+.

### Frontend

```bash
cd frontend
npm install
npm run dev -- --host 0.0.0.0
```

## Offline editor assets

The code editor (Monaco) is bundled from the locally installed `monaco-editor` package instead of being downloaded from a public CDN at runtime. `frontend/src/monacoSetup.ts` hands the bundled instance to `@monaco-editor/react` (`loader.config({ monaco })`) and registers the editor, JSON, CSS, HTML and TypeScript workers as local Vite assets (`?worker` imports). No file in the default runtime path is fetched from jsDelivr, unpkg or any other remote host, so the editor keeps working with network access disabled. `npm run dev` pre-bundles the editor from `node_modules` and the production build emits it into `frontend/dist/assets`.

## Security and privacy constraints

- No silent code modification
- No automatic apply without explicit approval
- No cloud AI upload by default
- Local/on-device inference preferred
- No secret credentials checked into source control
- No runtime CDN dependency for the code editor: Monaco and its workers are served from the application's own bundled assets

## Qualcomm integration note

The `QualcommAiAdapter` interface is preserved as the integration point for Qualcomm AI Hub / GenieX / QAIRT plus Snapdragon Hexagon NPU workflows. The active implementation is `RuntimeCapabilityQualcommAdapter`, which reports runtime capability instead of claiming execution. `QualcommRuntimeDetector` (default implementation `SystemQualcommRuntimeDetector`) observes the host operating system, CPU architecture and any QNN/QAIRT/SNPE runtime components that actually exist on the machine, while `QualcommProperties` supplies the configured deployment mode, model id and execution target.

The verified-execution verdict comes from `QualcommVerification` (default implementation `QualcommVerificationService`), which requires observed evidence: a loopback GenieX server that answers `/v1/models` and a chat completion, plus a `geniex-bench` artifact that records the NPU compute unit, agrees with the served model and is not stale. `GenieXServerProbe` and `GenieXBenchArtifactReader` gather that evidence, `GenieXCliProbe` notes whether a local launcher exists (informational only, because in a Qualcomm Device Cloud session the launcher runs on the device), and every blocked gate leaves a reason code in `qualcommVerificationReasons`. `analyze` still never executes a model, never fabricates a result and returns the input snippet unchanged with an uncalibrated confidence. A Snapdragon deployment can supply its own detector, its own `QualcommVerification` or a QNN backed `QualcommAiAdapter` implementation without changing the application core. See `docs/QUALCOMM-GENIEX.md` and `docs/evidence/README.md`.


# CodeGuard AI - Local Demo Setup

This document describes how to run CodeGuard AI end to end on a local
developer machine using a **real** local model. No cloud service, no stub and
no canned response is involved.

## Execution path

```text
React + Vite (frontend, :5173)
   -> Spring Boot (backend, :8080)
      -> PMD + Checkstyle + SpotBugs (real static analysis)
      -> HttpLocalAiInferenceProvider
         -> http://127.0.0.1:8081  (llama-server, loopback only)
            -> qwen2.5-coder-1.5b-instruct-q4_k_m.gguf
   -> AI response rendered in the UI
```

## Prerequisites

| Requirement | Version used for verification |
|---|---|
| Java | 21 (Microsoft OpenJDK 21) |
| Maven | 3.9.x |
| Node.js | 18+ with npm |
| Disk | ~1.1 GB for the model, ~20 MB for the runtime |

## 1. Download the pinned llama.cpp runtime

```powershell
.\scripts\fetch-llama.ps1
```

Downloads and extracts the verified build into `tools/llama/b11226/`:

| Field | Value |
|---|---|
| Release | `b11226` (llama.cpp) |
| Asset | `llama-b11226-bin-win-cpu-x64.zip` |
| Size | 19,156,332 bytes |
| SHA-256 | `F0C4E2C65A07FFB47136D7703549912BEC908FA641A26D2C2D6AA01E7ED48E87` |

The script verifies the checksum and fails on mismatch.

## 2. Download the demo model

```powershell
.\scripts\fetch-model.ps1
```

| Field | Value |
|---|---|
| Model | Qwen2.5-Coder-1.5B-Instruct |
| Quantization | `Q4_K_M` |
| Artifact | `qwen2.5-coder-1.5b-instruct-q4_k_m.gguf` |
| Source | `https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF` |
| Size | 1,117,320,768 bytes (~1.04 GB) |
| SHA-256 | `CC324AF070C2ECBFD324A30884D2F951A7FF756ABA85CB811A6EC436933BB046` |
| License | Apache-2.0 (Qwen2.5-Coder-1.5B-Instruct) |

The model is **not** committed to the repository and is **not** downloaded at
application startup. It is fetched only when this script is run explicitly.

The download is resumable: re-running the script continues a partial transfer.
The checksum is compared against the value above and reported as a warning if
upstream has published a new revision.

> If the upstream repository changes the artifact, the size guard still runs.
> Treat a checksum warning as a signal to re-verify the source, not as a
> failure.

## 3. Start the local inference server

```powershell
.\scripts\start-llama.ps1
```

This launches the real `llama-server.exe` with the model above, bound to
`127.0.0.1` only. The `--alias` flag is set to the model filename so the
identifier matches `codeguard.ai.model` in `application.yml`.

Wait for the line:

```text
llama_server: listening on http://127.0.0.1:8081
```

Verify independently:

```powershell
curl.exe http://127.0.0.1:8081/v1/models
```

Expected model id: `qwen2.5-coder-1.5b-instruct-q4_k_m.gguf`

## 4. Start the backend

```powershell
cd backend
mvn spring-boot:run
```

Backend listens on `http://localhost:8080`.

Check AI reachability:

```powershell
curl.exe http://localhost:8080/api/runtime
```

Relevant fields:

| Field | Meaning |
|---|---|
| `aiConfigured` | configuration exists |
| `aiProviderReachable` | a real request to the loopback provider succeeded |
| `aiModelServed` | the provider reports the configured model |
| `aiStatus` | `responding` / `unreachable` / `skipped` |
| `aiDetails` | factual description |

`aiProviderReachable` is produced by an actual `GET /v1/models` call. When
llama-server is not running it reports `false` and `aiStatus` is `unreachable`;
availability is never inferred from configuration alone.

To speed up analysis you can enable the optional CodeGuard heuristic layer:

```powershell
$env:CODEGUARD_HEURISTICS_ENABLED = "true"
```

or set `codeguard.heuristics.enabled=true` in `application.yml`. When enabled,
those findings are reported under the analyzer name `CodeGuard-Heuristics` and
are **not** attributed to PMD, Checkstyle or SpotBugs.

## 5. Start the frontend

```powershell
cd frontend
npm install
npm run dev
```

Open the URL printed by Vite (default <http://localhost:5173>). The dev server
proxies `/api` to `http://localhost:8080`.

## 6. Run the demo

1. Pick a sample snippet (for example the SQL injection sample) or paste your
   own Java code into the editor.
2. Press **Scan**. Findings from the real analyzers appear in the list.
3. Select a finding and press **Analyze**. This calls the local model through
   `HttpLocalAiInferenceProvider`.
4. The explanation, root cause, risk and recommended fix come from the local
   GGUF model and are rendered in the detail panel.
5. Press **Propose fix** to request a corrected snippet, review the diff, then
   apply it. The correction is only written when the affected source range
   matches the finding metadata; otherwise the API answers `409` with
   `status: "requires-review"` and `sourceModified: false`, and nothing changes.
6. Press **Verify scan** to re-run the analyzers and compare findings.

## Behavior when the model is not running

Stop llama-server and press **Analyze** again. The backend returns a clearly
degraded response with `confidence: "uncalibrated"` and an explanation that the
local provider was unavailable. Static analysis is unaffected: scanning,
findings, fix application and verification all continue to work.

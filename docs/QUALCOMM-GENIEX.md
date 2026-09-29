# Qualcomm GenieX NPU verification

This runbook describes how CodeGuard decides that "Snapdragon/NPU execution is
verified", and how to produce the evidence it requires on a Snapdragon device
(local hardware or a Qualcomm Device Cloud session).

The short version: **configuration never proves anything.** A flag in
`application.yml` states intent. The runtime report only claims a verified NPU
when a live GenieX server round trip and a `geniex-bench` result that records the
NPU compute unit both exist and agree on the model that was run.

## Components

| Piece | Role |
| --- | --- |
| `QualcommProperties` (`codeguard.qualcomm`) | All settings: GenieX server, evidence rules, launcher paths. Rejects any non-loopback `server.base-url`. |
| `GenieXServerProbe` | Real HTTP calls to `/health`, `/v1/models` and `/v1/chat/completions` on the loopback GenieX server. |
| `GenieXBenchArtifactReader` | Reads a `geniex-bench --output-json` artifact with tolerant key lookup. Read only; it never writes evidence. |
| `GenieXCliProbe` | Notices a local `geniex` launcher by file existence only. Informational. |
| `QualcommVerificationService` | The six gates below, producing a tier, reason codes and the evidence record. |
| `RuntimeCapabilityQualcommAdapter` | Publishes the verdict through `QualcommRuntimeStatus` and `GET /api/runtime`. |

Inference itself is not implemented by the adapter. It runs through the existing
OpenAI-compatible provider stack (`HttpLocalAiInferenceProvider`) pointed at the
GenieX server, so the same code path serves `llama-server` and `geniex serve`.

## The six gates

`qualcommNpuVerified` is `true` only when all required gates pass.

| Gate | Checks | Failure reason codes |
| --- | --- | --- |
| 1. host | The machine is ARM64, or `verification.allow-remote-device=true` and the artifact names the device | `host-not-arm64`, `remote-evidence-without-device-label` |
| 2. http-live | A GenieX server answered and serves the configured model | `server-probe-disabled`, `server-unreachable`, `model-not-served` |
| 3. http-inference | A chat completion returned non-empty content | `inference-probe-disabled`, `inference-empty-response`, `inference-probe-failed` |
| 4. artifact-device | A benchmark artifact exists, parses, and records the required compute unit | `artifact-missing`, `artifact-unreadable`, `artifact-device-missing`, `artifact-device-mismatch` |
| 5. agreement | The server and the artifact describe the same model, and the chipset does not contradict the configuration | `http-artifact-mismatch`, `artifact-chipset-mismatch` |
| 6. freshness | The artifact is newer than `verification.max-artifact-age-days` | `artifact-stale` |

Gate 4 exists because the GenieX HTTP API never states which compute unit
executed a request. Gate 5 exists because a CPU fallback answers just as well as
an NPU: without it, "inference worked" would be presented as "the NPU ran".

### Verification modes

`codeguard.qualcomm.verification.mode`:

| Mode | Requires | Can report a verified NPU |
| --- | --- | --- |
| `both` (default) | gates 1-6 | yes |
| `artifact` | gates 1, 4, 5, 6 | yes (for example for an offline device) |
| `http` | gates 1-3 | no, always; a server answer cannot name the compute unit |
| `disabled` | nothing is probed | no |

### Tiers reported by `qualcommVerificationTier`

| Tier | Meaning |
| --- | --- |
| `npu-verified` | All required gates passed. |
| `server-reachable` | A live completion ran, but the compute unit is not proven. |
| `no-evidence` | Nothing usable was observed. |
| `configuration-only` | No verification bean; host facts plus a configured flag only. |
| `disabled` | Verification is off. |

## Run 1: local Snapdragon device

On the device:

```powershell
# 1. Serve the model on loopback with the Hexagon plugin.
geniex serve qualcommiq/Llama-3.1-8B-Instruct `
    --plugin-genie libQnnHtp.so `
    --device npu `
    --connection-mode local

# 2. In another shell, capture the evidence.
.\scripts\verify-geniex-npu.ps1 -Model qualcommiq/Llama-3.1-8B-Instruct
```

Then in `application.yml`:

```yaml
codeguard:
  ai:
    provider: geniex
    base-url: http://127.0.0.1:18181
    model: qualcommiq/Llama-3.1-8B-Instruct:8b-precision-rr-20250504
    runtime: geniex-llama-cpp-npu
  qualcomm:
    server:
      enabled: true
      base-url: http://127.0.0.1:18181
      model: qualcommiq/Llama-3.1-8B-Instruct:8b-precision-rr-20250504
    verification:
      mode: both
      artifact-path: artifacts/qualcomm/geniex-bench.json
      chipset-label: Snapdragon X Elite
```

`GET /api/runtime` then reports `qualcommVerificationTier: npu-verified` and
`qualcommNpuVerified: true`.

## Run 2: Qualcomm Device Cloud session

A QDC device runs GenieX on its own loopback, and its API has no authentication,
so the port is forwarded to the workstation instead of published:

```powershell
.\scripts\start-geniex-tunnel.ps1 -SshHost 203.0.113.10 -SshUser your-id
.\scripts\verify-geniex-npu.ps1 -Model <served-model-id> -Chipset "Snapdragon X Elite" -HostLabel "sqd-x-elite-01"
```

Because CodeGuard then runs on an x86_64 workstation, add the opt-in that says
the evidence describes a remote device:

```yaml
codeguard:
  qualcomm:
    verification:
      allow-remote-device: true
```

Without it, gate 1 fails with `host-not-arm64`. With it, the artifact must also
name the device (`chipset` or `host`), otherwise the run is refused with
`remote-evidence-without-device-label`. The evidence record then carries
`remoteDeviceEvidence: true`, so the claim can never be read as "this laptop has
an NPU".

## Using GenieX for analysis requests

Nothing in the Qualcomm adapter is needed for this; it is the same provider stack
as `llama-server`:

```yaml
codeguard:
  ai:
    provider: geniex
    base-url: http://127.0.0.1:18181   # loopback only, tunneled if remote
    model: <identifier reported by GET /v1/models>
    runtime: geniex-llama-cpp-npu
```

The model identifier must match what the server reports. GenieX identifiers
usually carry a precision suffix (`org/repo:8b-precision-...`); the configured
value may include or omit it, and the provider health probe compares both forms.

## The evidence artifact

Default location: `artifacts/qualcomm/geniex-bench.json` (relative to the working
directory of the backend). `docs/evidence/README.md` describes the shape and the
rules for committing it.

Qualcomm does not publish the `geniex-bench --output-json` schema, so
`GenieXBenchArtifactReader` locates values by alias, breadth first, and reports
anything it cannot find as missing. When a real capture shows a new key, add it
to the relevant alias list in `GenieXBenchArtifactReader` and to
`docs/evidence/README.md`; do not guess values.

## Troubleshooting

| Reason code | What to do |
| --- | --- |
| `server-probe-disabled` | Set `codeguard.qualcomm.server.enabled: true`. |
| `server-unreachable` | Start `geniex serve ... --connection-mode local`, or open the tunnel. |
| `model-not-served` | Set `codeguard.qualcomm.server.model` to an identifier from `GET /v1/models`; the detail lists what is served. |
| `inference-probe-disabled` | `inference-probe-enabled=false` stops the round trip; mode `both` cannot pass without it. |
| `artifact-missing` | Run `scripts/verify-geniex-npu.ps1` where the artifact path is readable by the backend. |
| `artifact-device-missing` | The artifact did not name a compute unit. `geniex-bench` must record one, or pass `-Device` only after observing the run. |
| `artifact-device-mismatch` | The benchmark ran on another unit (for example a CPU fallback). Fix the run; do not raise `require-device`. |
| `http-artifact-mismatch` | Server and benchmark ran different models. Re-run both against the same model. |
| `artifact-stale` | The evidence is older than `max-artifact-age-days`. Re-capture it. |
| `host-not-arm64` | Expected on a non ARM64 host. Set `allow-remote-device: true` when the evidence really is from a remote Snapdragon device. |

## Known limitations

- The `geniex-bench` JSON schema is unpublished; the reader tolerates aliases but
  a future key layout may need a code change, and the raw capture path is printed
  by the verification script so the schema can be added from a real run.
- Gate 5 compares model identifiers leniently (a cache path and an identifier are
  considered the same model) because the benchmark commonly records the local
  cache directory. It rejects clearly different models, not spelling differences.
- Verification performs real HTTP calls to the configured loopback server and
  reads one file. Both are bounded by the configured timeouts, and both are
  skipped when `server.enabled` is `false` or the mode is `disabled`.
- No genuine `verified=true` state can be produced on an Intel or AMD machine: the
  host gate refuses it unless remote device evidence is explicitly enabled and
  labelled.

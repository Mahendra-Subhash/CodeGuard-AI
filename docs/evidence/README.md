# Qualcomm evidence artifacts

Files here are the observed evidence behind the "Snapdragon/NPU execution is
verified" claim. The backend only ever reads them
(`GenieXBenchArtifactReader`); it never writes them.

## Location

The backend reads `codeguard.qualcomm.verification.artifact-path`, which defaults
to `artifacts/qualcomm/geniex-bench.json` relative to the backend working
directory. `docs/QUALCOMM-GENIEX.md` is the runbook that produces it.

## Shape

`scripts/verify-geniex-npu.ps1` writes this normalized shape, built from a real
`geniex-bench --output-json` run plus the observed HTTP round trip:

```json
{
  "schema": "codeguard.geniex-bench/1",
  "captured_at": "2026-09-29T14:05:11.482Z",
  "device": "npu",
  "device_source": "geniex-bench",
  "plugin": "llama.cpp-qt",
  "model": "qualcommiq/Llama-3.1-8B-Instruct:8b-precision-rr-20250504",
  "chipset": "Snapdragon X Elite",
  "host": "sqd-x-elite-01",
  "statistics": {
    "stats.tokens_per_second": 31.5,
    "stats.ttft_ms": 120,
    "http.latency_ms": 1187,
    "http.completion_tokens": 3
  },
  "raw_bench_output": "%TEMP%\\geniex-bench-2f1c.json"
}
```

Field meaning:

| Field | Meaning |
| --- | --- |
| `captured_at` | When the evidence was captured (UTC, ISO-8601). Drives the freshness gate. |
| `device` | The compute unit the benchmark recorded. Only `npu` supports the NPU claim. |
| `device_source` | `geniex-bench` when the benchmark reported it, `operator-assertion` when `-Device` was used, `unavailable` when neither did. The backend refuses the last two cases only through the value itself, so an assertion is visible in the file. |
| `plugin`, `model`, `chipset`, `host` | Copied from the benchmark run. Used for the agreement gate. |
| `statistics` | Numbers copied verbatim, keyed by their dotted JSON path. Nothing is renamed or recalculated except the `http.*` entries, which the script measured itself. |
| `raw_bench_output` | Where the unparsed benchmark output was kept, so the reader's alias lists can be extended from a real capture. |

## Rules

1. Capture with `scripts/verify-geniex-npu.ps1`. Do not hand-write an artifact:
   an artifact is a record of an observed run, and a hand-written one is
   indistinguishable from a real one in the report.
2. Never edit `device` to make verification pass. `artifact-device-mismatch` and
   `artifact-device-missing` are the honest outcomes when a run did not prove
   NPU execution.
3. Keep the raw benchmark output next to the artifact until the alias lists in
   `GenieXBenchArtifactReader` cover every key it contains. The `geniex-bench`
   schema is not published, so a future GenieX release may add keys.
4. If a capture proves NPU execution on a device that is not the machine running
   CodeGuard, record the `chipset` and `host` values and set
   `codeguard.qualcomm.verification.allow-remote-device: true`. The evidence is
   then reported with `remoteDeviceEvidence: true`.
5. Artifacts older than `codeguard.qualcomm.verification.max-artifact-age-days`
   stop being accepted. Re-capture rather than extending the limit.
6. `artifacts/` is git-ignored: a capture belongs to one device and one run. If a
   deployment wants to ship the artifact that proved its NPU claim, commit it
   explicitly and keep the capture date in the commit message.

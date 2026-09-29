/*
 * ============================================================
 * RUNTIME STATUS
 * ============================================================
 *
 * Derives the compact runtime indicator from GET /api/runtime.
 *
 * Facts only:
 *  - "Ready" is only shown for facts the backend actually reported.
 *  - The AI provider is never reported as connected from configuration alone;
 *    the backend probes it and reports "responding" / "unreachable" / "skipped".
 *  - Qualcomm is never described as active. NPU execution is only shown as
 *    verified when the backend says it verified it.
 */

import type { RuntimeInfo } from '../api/types';

export type Tone = 'success' | 'warn' | 'danger' | 'neutral';

export type StatusRow = {
  label: string;
  value: string;
  tone: Tone;
};

export type AiAvailability = 'ready' | 'offline' | 'unknown';

export type RuntimeSummary = {
  rows: StatusRow[];
  aiAvailability: AiAvailability;
  /** The AI model name the backend is configured with, when reported. */
  model: string | null;
  aiDetail: string | null;
  /** Reason codes for the gates that blocked the NPU claim, when any blocked. */
  qualcommDetail: string | null;
};

const TIER_TEXT: Record<string, string> = {
  'npu-verified': 'Verified (NPU)',
  'server-reachable': 'Server reachable, compute unit unproven',
  'no-evidence': 'Not verified (no evidence)',
  'configuration-only': 'Not verified (host facts only)',
  disabled: 'Not verified (verification disabled)',
};

/**
 * The Qualcomm line always states the tier the backend reported. A verified NPU
 * is shown only when the backend says so; every other tier is spelled out, so
 * "inference worked" is never displayed as "NPU verified".
 */
export function describeQualcommTier(runtime: RuntimeInfo): string {
  if (runtime.qualcommNpuVerified === true) {
    return TIER_TEXT['npu-verified'];
  }
  return TIER_TEXT[runtime.qualcommVerificationTier ?? ''] ?? 'Not verified';
}

export function qualcommTone(runtime: RuntimeInfo): Tone {
  if (runtime.qualcommNpuVerified === true) {
    return 'success';
  }
  if (runtime.qualcommVerificationTier === 'server-reachable') {
    return 'warn';
  }
  return 'neutral';
}

/**
 * Only the failing gates are shown. Reason codes of gates that passed and the
 * informational launcher note would bury the reason the claim was refused.
 */
function qualcommDetailFor(runtime: RuntimeInfo): string | null {
  const failures = (runtime.qualcommVerificationReasons ?? []).filter(
    (reason) =>
      !reason.startsWith('gate-passed:') && !reason.startsWith('geniex-cli-')
  );
  return failures.length > 0 ? failures.join(' · ') : null;
}

export function describeRuntime(
  runtime: RuntimeInfo | null,
  loading: boolean
): RuntimeSummary {

  if (!runtime) {
    return {
      rows: [
        {
          label: 'Static Analysis',
          value: loading ? 'Checking...' : 'Unknown',
          tone: loading ? 'neutral' : 'warn',
        },
        {
          label: 'AI Provider',
          value: loading ? 'Checking...' : 'Unknown',
          tone: loading ? 'neutral' : 'warn',
        },
        {
          label: 'AI Model',
          value: loading ? 'Checking...' : 'Unknown',
          tone: loading ? 'neutral' : 'warn',
        },
        {
          label: 'Qualcomm acceleration',
          value: 'Not verified',
          tone: 'neutral',
        },
      ],
      aiAvailability: 'unknown',
      model: null,
      aiDetail: null,
      qualcommDetail: null,
    };
  }

  const availability = aiAvailability(runtime);

  return {
    rows: [
      {
        label: 'Static Analysis',
        value: 'Ready',
        tone: 'success',
      },
      {
        label: 'AI Provider',
        value: providerValue(runtime, availability),
        tone: providerTone(availability),
      },
      {
        label: 'AI Model',
        value: modelValue(runtime, availability),
        tone: modelTone(runtime, availability),
      },
      {
        label: 'Qualcomm acceleration',
        value: describeQualcommTier(runtime),
        tone: qualcommTone(runtime),
      },
    ],
    aiAvailability: availability,
    model: runtime.model ?? null,
    aiDetail: detailFor(runtime, availability),
    qualcommDetail: qualcommDetailFor(runtime),
  };
}

/**
 * Availability is derived from the backend probe, never from configuration.
 */
export function aiAvailability(runtime: RuntimeInfo | null): AiAvailability {

  if (!runtime) {
    return 'unknown';
  }

  if (runtime.aiStatus === 'responding') {
    return runtime.aiModelServed === true ? 'ready' : 'unknown';
  }

  if (runtime.aiStatus === 'unreachable') {
    return 'offline';
  }

  /*
   * The health probe was skipped or the status is unknown: the dashboard must
   * not claim either state.
   */
  return 'unknown';
}

function providerValue(
  runtime: RuntimeInfo,
  availability: AiAvailability
): string {

  if (availability === 'ready') {
    return 'Connected';
  }

  if (availability === 'offline') {
    return 'Offline';
  }

  if (runtime.aiProviderReachable === true) {
    return 'Connected (model not served)';
  }

  return 'Not probed';
}

function providerTone(availability: AiAvailability): Tone {

  if (availability === 'ready') {
    return 'success';
  }

  if (availability === 'offline') {
    return 'danger';
  }

  return 'warn';
}

function modelValue(
  runtime: RuntimeInfo,
  availability: AiAvailability
): string {

  if (availability === 'ready') {
    return runtime.model ?? 'Ready';
  }

  if (availability === 'offline') {
    return 'Unavailable';
  }

  if (runtime.aiProviderReachable === true) {
    return 'Configured model not served';
  }

  return runtime.model ?? 'Unknown';
}

function modelTone(
  runtime: RuntimeInfo,
  availability: AiAvailability
): Tone {

  if (availability === 'ready') {
    return 'success';
  }

  if (availability === 'offline') {
    return 'danger';
  }

  return 'warn';
}

function detailFor(
  runtime: RuntimeInfo,
  availability: AiAvailability
): string | null {

  if (availability === 'ready') {
    return runtime.aiDetails ?? null;
  }

  if (availability === 'offline') {
    return (
      runtime.aiDetails ??
      `No local AI provider is responding at ${runtime.aiBaseUrl ?? '127.0.0.1:8081'}.`
    );
  }

  return runtime.aiDetails ?? null;
}

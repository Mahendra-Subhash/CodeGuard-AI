/*
 * ============================================================
 * RUNTIME STATUS BAR
 * ============================================================
 *
 * Compact indicator for what is actually available:
 *
 *   Static Analysis: Ready
 *   AI Provider: Connected / Offline
 *   AI Model: Ready / Unavailable
 *   Qualcomm acceleration: Not verified
 *
 * Every value comes from GET /api/runtime. Nothing is inferred from
 * configuration and no accelerator is ever described as active.
 */

import AiUnavailableNotice from './AiUnavailableNotice';
import type { RuntimeInfo } from '../api/types';
import type { RuntimeSummary } from '../ux/runtimeStatus';

type Props = {
  runtime: RuntimeInfo | null;
  summary: RuntimeSummary;
  loading: boolean;
  onRecheck: () => void;
};

export default function RuntimeStatusBar({
  runtime,
  summary,
  loading,
  onRecheck,
}: Props) {

  return (
    <section
      className="card runtime-bar"
      aria-label="Runtime status"
    >
      <h3>Runtime status</h3>

      <ul className="runtime-rows">

        {summary.rows.map((row) => (
          <li
            key={row.label}
            className={`runtime-row ${row.tone}`}
          >
            <span className="runtime-label">
              {row.label}:
            </span>{' '}

            <span className="runtime-value">
              {row.value}
            </span>
          </li>
        ))}

      </ul>

      {loading && (
        <p className="muted status-live-line">
          Checking the local AI provider...
        </p>
      )}

      {!loading && summary.aiDetail && (
        <p className="muted status-live-line">
          {summary.aiDetail}
        </p>
      )}

      <p className="muted runtime-facts">
        Provider: {runtime?.provider ?? 'Unavailable'}
        {' · '}
        Model: {runtime?.model ?? 'Unavailable'}
        {' · '}
        Runtime: {runtime?.runtime ?? 'Unavailable'}
        {' · '}
        Privacy mode: {runtime?.privacyMode ? 'Enabled' : 'Disabled'}
      </p>

      <p className="muted runtime-facts">
        Qualcomm deployment mode: {runtime?.qualcommDeploymentMode ?? 'development'}
        {' · '}
        Qualcomm execution target: {runtime?.qualcommExecutionTarget ?? 'not configured'}
        {' · '}
        Qualcomm NPU execution: {runtime?.qualcommNpuVerified ? 'verified' : 'not verified'}
      </p>

      {runtime?.qualcommVerificationTier && (
        <p className="muted runtime-facts">
          NPU verification tier: {runtime.qualcommVerificationTier}
          {runtime.qualcommVerificationEvidence?.computeUnit
            ? ` (artifact compute unit: ${runtime.qualcommVerificationEvidence.computeUnit})`
            : ''}
        </p>
      )}

      {summary.qualcommDetail && (
        <p className="muted status-live-line">
          NPU verification blocked by: {summary.qualcommDetail}
        </p>
      )}

      {runtime?.qualcommVerificationMessage && (
        <p className="muted runtime-facts">
          {runtime.qualcommVerificationMessage}
        </p>
      )}

      {runtime?.qualcommDetails && (
        <p className="muted runtime-facts">
          {runtime.qualcommDetails}
        </p>
      )}

      {summary.aiAvailability !== 'offline' && (
        <div className="button-row">

          <button
            type="button"
            className="secondary"
            onClick={onRecheck}
            disabled={loading}
            aria-busy={loading}
          >
            {loading ? 'Checking...' : 'Recheck AI status'}
          </button>

        </div>
      )}

      {!loading && summary.aiAvailability === 'offline' && (
        <div className="space-top">
          <AiUnavailableNotice
            baseUrl={runtime?.aiBaseUrl}
            detail={summary.aiDetail}
            onRecheck={onRecheck}
            rechecking={loading}
          />
        </div>
      )}

    </section>
  );
}

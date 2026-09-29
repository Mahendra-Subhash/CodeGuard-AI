/*
 * ============================================================
 * PROPOSED FIX
 * ============================================================
 *
 * The proposed code is visually separated from the explanation, and the panel
 * states clearly who does what:
 *
 *   - the AI proposes the change
 *   - CodeGuard's safety layer independently validates whether it can be
 *     written, at apply time
 *
 * "Safe to apply" is never claimed here: before the apply there is no backend
 * validation result to show, so the panel says the proposal is not validated
 * yet.
 */

import StatusBanner from './StatusBanner';
import type { FixProposal } from '../api/types';
import { OPERATION_LABELS, type Operation } from '../ux/operation';

type Props = {
  proposal: FixProposal;
  /** True when the backend returned a fallback proposal instead of model output. */
  degraded: boolean;
  /** Model reported confidence from the explanation request, when available. */
  confidence: string | null;
  busy: Operation | null;
  onApply: () => void;
  onReject: () => void;
};

export default function ProposedFixPanel({
  proposal,
  degraded,
  confidence,
  busy,
  onApply,
  onReject,
}: Props) {

  const applying = busy === 'apply-fix';

  if (degraded) {
    return (
      <div className="detail-block space-top">

        <h4>Proposed fix</h4>

        <StatusBanner
          tone="warn"
          title="No usable AI fix proposal"
          message="The local AI provider did not return a change for this finding, so there is nothing to apply. CodeGuard left your source untouched."
          detail={proposal.rationale}
          onDismiss={onReject}
          dismissLabel="Close proposal"
        />

      </div>
    );
  }

  return (
    <div className="detail-block space-top proposal-panel">

      <div className="proposal-head">

        <h4>Proposed fix</h4>

        <span className="safety-chip neutral">
          Not validated yet
        </span>

      </div>

      <p className="safety-note">
        The AI proposes this change. CodeGuard's safety layer independently
        validates whether it can be applied, and nothing is written to your
        source until you apply it.
      </p>

      {proposal.rationale && (
        <p>
          <strong>Rationale:</strong> {proposal.rationale}
        </p>
      )}

      {proposal.proposedCode && (
        <>
          <h5>Proposed code</h5>

          <pre className="code-block proposed-code">
            {proposal.proposedCode}
          </pre>
        </>
      )}

      {proposal.diff && (
        <>
          <h5>Diff</h5>

          <pre className="code-block diff-block">
            {proposal.diff}
          </pre>
        </>
      )}

      {confidence && (
        <p className="confidence-line">
          <strong>Confidence:</strong> {confidence}
        </p>
      )}

      <div className="button-row">

        <button
          type="button"
          className="approve"
          onClick={onApply}
          disabled={busy !== null}
          aria-busy={applying}
        >
          {applying ? OPERATION_LABELS['apply-fix'] : 'Apply Fix'}
        </button>

        <button
          type="button"
          className="reject"
          onClick={onReject}
          disabled={busy !== null}
        >
          Reject
        </button>

      </div>

    </div>
  );
}

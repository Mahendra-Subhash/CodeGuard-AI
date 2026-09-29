/*
 * ============================================================
 * APPLY-FIX OUTCOME
 * ============================================================
 *
 * Renders the classified outcome of an apply-fix attempt.
 *
 * The wording and the tone come from `ux/fixOutcome.ts`, which is unit tested,
 * so the dashboard can never fall back to a generic "the fix could not be
 * applied" message. In particular:
 *
 *  - requires-review says explicitly that no changes were made
 *  - already-applied is not presented as an error
 *  - a validation failure only claims "safe" when sourceModified is true
 */

import StatusBanner from './StatusBanner';
import type { FixOutcome } from '../ux/fixOutcome';

type Props = {
  outcome: FixOutcome;
  onDismiss: () => void;
};

export default function FixOutcomePanel({
  outcome,
  onDismiss,
}: Props) {

  const detail = [
    outcome.detail,
    outcome.changedLines
      ? `Verified line range: ${outcome.changedLines.start}-${outcome.changedLines.end}.`
      : undefined,
  ]
    .filter((entry): entry is string => Boolean(entry))
    .join(' ');

  return (
    <section className="space-top outcome-panel">

      <StatusBanner
        tone={outcome.tone}
        title={outcome.title}
        message={outcome.message}
        detail={detail.length > 0 ? detail : undefined}
        onDismiss={onDismiss}
        dismissLabel="Dismiss result"
      />

      <p className="safety-note">

        {outcome.kind === 'applied' && (
          <>
            <strong>Safety layer:</strong> CodeGuard validated the proposed
            change against the current source before writing it. The AI did not
            write anything itself.
          </>
        )}

        {outcome.kind === 'already-applied' && (
          <>
            <strong>Safety layer:</strong> the proposed change is already
            present in the current source, so nothing was written.
          </>
        )}

        {outcome.kind === 'requires-review' && (
          <>
            <strong>Safety layer:</strong> the proposed change was refused.
            Source modified: {outcome.sourceModified ? 'yes' : 'no'}.
          </>
        )}

        {outcome.kind !== 'applied' &&
          outcome.kind !== 'already-applied' &&
          outcome.kind !== 'requires-review' && (
            <>
              <strong>Safety layer:</strong> the operation did not complete, so
              nothing was written.
            </>
          )}

      </p>

    </section>
  );
}

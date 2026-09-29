/*
 * ============================================================
 * APPLY-FIX OUTCOME
 * ============================================================
 *
 * The apply-fix endpoint has four real outcomes and the dashboard must tell
 * them apart instead of collapsing everything into "the fix could not be
 * applied":
 *
 *  1. 200 + rewritten source        -> the fix was applied
 *  2. 200 + unchanged source        -> the fix is already applied
 *  3. 409 + sourceModified=false    -> requires review, nothing was written
 *  4. transport/5xx failure         -> the operation failed
 *
 * "Safe" is only ever claimed for outcomes the backend validated.
 */

import type { ApiFailureDescriptor, FixConflict, Scan } from '../api/types';
import { describeFailure } from './errors';

export type FixOutcomeKind =
  | 'applied'
  | 'already-applied'
  | 'requires-review'
  | 'validation'
  | 'not-found'
  | 'server'
  | 'network'
  | 'unknown';

export type FixOutcomeTone = 'success' | 'warn' | 'danger' | 'neutral';

export type FixOutcome = {
  kind: FixOutcomeKind;
  tone: FixOutcomeTone;
  /** Headline, e.g. "Fix applied successfully." */
  title: string;
  message: string;
  /** Backend provided reason, when there is one. */
  detail?: string;
  /** Only true when the backend really rewrote the stored source. */
  sourceModified: boolean;
  /** Findings left after the latest analysis, when known. */
  remainingFindings?: number;
  /** Line range the verified patch wrote, when it can be derived. */
  changedLines?: { start: number; end: number };
};

export type ApplyFixInput =
  | {
      type: 'response';
      previousSourceCode: string;
      updatedScan: Scan;
    }
  | {
      type: 'rejected';
      conflict: FixConflict;
    }
  | {
      type: 'failure';
      failure: ApiFailureDescriptor;
      conflict?: FixConflict;
    };

export function describeFixOutcome(input: ApplyFixInput): FixOutcome {

  if (input.type === 'response') {
    return describeAcceptedFix(
      input.previousSourceCode,
      input.updatedScan
    );
  }

  if (input.type === 'rejected') {
    return describeRejectedFix(input.conflict);
  }

  /*
   * A 409 is not a server failure: it is the safety layer refusing to write.
   */
  if (
    input.failure.status === 409 ||
    input.conflict?.status === 'requires-review'
  ) {
    return describeRejectedFix(input.conflict ?? {});
  }

  const failure = describeFailure(input.failure, 'Applying the fix');

  return {
    kind: failure.kind,
    tone: 'danger',
    title: failure.title,
    message: failure.message,
    detail: failure.detail,
    sourceModified: false,
  };
}

function describeAcceptedFix(
  previousSourceCode: string,
  updatedScan: Scan
): FixOutcome {

  const remainingFindings = updatedScan.findings?.length ?? 0;

  /*
   * The endpoint answers 200 for both "applied" and "already applied". The
   * difference is the source itself: the safety layer only reports success
   * after writing the verified range, so an unchanged source means the
   * correction was already present.
   */
  if (!sourceChanged(previousSourceCode, updatedScan.sourceCode)) {
    return {
      kind: 'already-applied',
      tone: 'neutral',
      title: 'This fix is already applied.',
      message:
        'The current source already contains the proposed change, so nothing was written.',
      sourceModified: false,
      remainingFindings,
    };
  }

  const changedLines = changedLineRange(
    previousSourceCode,
    updatedScan.sourceCode
  );

  return {
    kind: 'applied',
    tone: 'success',
    title: 'Fix applied successfully.',
    message:
      remainingFindings === 0
        ? 'No findings remain in the updated source.'
        : remainingFindings === 1
          ? '1 finding remains.'
          : `${remainingFindings} findings remain.`,
    detail: changedLines
      ? `CodeGuard validated the change against the current source before writing lines ${changedLines.start}-${changedLines.end}.`
      : 'CodeGuard validated the change against the current source before writing it.',
    sourceModified: true,
    remainingFindings,
    changedLines: changedLines ?? undefined,
  };
}

function describeRejectedFix(conflict: FixConflict): FixOutcome {

  const backendReason = conflict.error?.trim();

  /*
   * The backend reports "already-applied" distinctly when it can tell that the
   * correction is already present. That is not an error.
   */
  if (conflict.status === 'already-applied') {
    return {
      kind: 'already-applied',
      tone: 'neutral',
      title: 'This fix is already applied.',
      message:
        'The current source already contains the proposed change, so nothing was written.',
      detail: backendReason,
      sourceModified: false,
    };
  }

  const range =
    typeof conflict.startLine === 'number' &&
    typeof conflict.endLine === 'number' &&
    conflict.startLine > 0
      ? ` (lines ${conflict.startLine}-${conflict.endLine})`
      : '';

  return {
    kind: 'requires-review',
    tone: 'warn',
    title: 'Fix requires review.',
    message:
      'The proposed change could not be safely verified against the current source. No changes were made.',
    detail: backendReason
      ? `${backendReason}${range}`
      : 'The proposed change could not be scoped to a verified line range. No changes were made.',
    sourceModified: conflict.sourceModified === true,
  };
}

/**
 * True when the stored source differs from the source that was sent.
 */
export function sourceChanged(
  previousSourceCode: string,
  updatedSourceCode: string | undefined
): boolean {

  if (typeof updatedSourceCode !== 'string') {
    return false;
  }

  return (
    normalizeNewlines(previousSourceCode) !==
    normalizeNewlines(updatedSourceCode)
  );
}

/**
 * First and last line that differ. Only used to describe what the verified
 * patch touched, so it is derived from the two real sources.
 */
export function changedLineRange(
  previousSourceCode: string,
  updatedSourceCode: string
): { start: number; end: number } | null {

  const before = normalizeNewlines(previousSourceCode).split('\n');
  const after = normalizeNewlines(updatedSourceCode).split('\n');

  let start = -1;
  let end = -1;

  const max = Math.max(before.length, after.length);

  for (let index = 0; index < max; index += 1) {

    if (before[index] !== after[index]) {

      if (start === -1) {
        start = index + 1;
      }

      end = index + 1;
    }
  }

  if (start === -1) {
    return null;
  }

  return { start, end };
}

function normalizeNewlines(value: string | undefined): string {
  return (value ?? '').replace(/\r\n/g, '\n');
}

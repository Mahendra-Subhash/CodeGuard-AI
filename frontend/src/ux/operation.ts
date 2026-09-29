/*
 * ============================================================
 * OPERATIONS
 * ============================================================
 *
 * One union for the operations the dashboard can run. It is used to:
 *
 *  - label the button that is running ("Scanning...", "Analyzing...", ...)
 *  - disable every other action while one is running
 */

export type Operation =
  | 'scan'
  | 'rescan'
  | 'analyze'
  | 'propose-fix'
  | 'apply-fix'
  | 'verify'
  | 'runtime';

export const OPERATION_LABELS: Record<Operation, string> = {
  scan: 'Scanning...',
  rescan: 'Re-scanning...',
  analyze: 'Analyzing...',
  'propose-fix': 'Generating fix...',
  'apply-fix': 'Applying fix...',
  verify: 'Verifying...',
  runtime: 'Checking...',
};

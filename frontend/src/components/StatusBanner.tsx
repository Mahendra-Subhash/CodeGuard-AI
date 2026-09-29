/*
 * ============================================================
 * STATUS BANNER
 * ============================================================
 *
 * One place for every success / warning / error / neutral message.
 *
 * Accessibility:
 *  - the state is carried by a text label and a symbol, not by color alone
 *  - errors are announced with role="alert", status updates with aria-live
 */

import type { ReactNode } from 'react';

export type StatusTone = 'success' | 'warn' | 'danger' | 'neutral';

type Props = {
  tone: StatusTone;
  title: string;
  message: string;
  detail?: string;
  /** Extra content, e.g. a "Recheck" button. */
  actions?: ReactNode;
  onDismiss?: () => void;
  dismissLabel?: string;
};

const TONE_LABEL: Record<StatusTone, string> = {
  success: 'Success',
  warn: 'Attention',
  danger: 'Error',
  neutral: 'Info',
};

const TONE_SYMBOL: Record<StatusTone, string> = {
  success: '\u2713',
  warn: '\u26a0',
  danger: '\u2715',
  neutral: '\u2139',
};

export default function StatusBanner({
  tone,
  title,
  message,
  detail,
  actions,
  onDismiss,
  dismissLabel = 'Dismiss',
}: Props) {

  return (
    <section
      className={`status-banner ${tone}`}
      role={tone === 'danger' ? 'alert' : 'status'}
      aria-live={tone === 'danger' ? 'assertive' : 'polite'}
    >
      <div className="status-banner-head">

        <span className="status-symbol" aria-hidden="true">
          {TONE_SYMBOL[tone]}
        </span>

        <span className="status-label">
          {TONE_LABEL[tone]}
        </span>

        <strong className="status-title">
          {title}
        </strong>

      </div>

      <p className="status-message">
        {message}
      </p>

      {detail && (
        <p className="status-detail">
          {detail}
        </p>
      )}

      {(actions || onDismiss) && (
        <div className="button-row">

          {actions}

          {onDismiss && (
            <button
              type="button"
              className="secondary"
              onClick={onDismiss}
            >
              {dismissLabel}
            </button>
          )}

        </div>
      )}

    </section>
  );
}

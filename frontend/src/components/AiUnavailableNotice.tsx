/*
 * ============================================================
 * AI UNAVAILABLE NOTICE
 * ============================================================
 *
 * Shown when the backend reports that the local inference provider is not
 * responding, so the dashboard never looks broken while static analysis is
 * still fully available.
 */

import StatusBanner from './StatusBanner';

type Props = {
  baseUrl?: string;
  detail?: string | null;
  onRecheck?: () => void;
  rechecking?: boolean;
};

export default function AiUnavailableNotice({
  baseUrl,
  detail,
  onRecheck,
  rechecking = false,
}: Props) {

  return (
    <StatusBanner
      tone="warn"
      title="AI assistant unavailable"
      message="CodeGuard can still perform PMD, Checkstyle and SpotBugs analysis. Start the local AI server to enable AI explanations and fixes."
      detail={
        detail ??
        (baseUrl
          ? `No local AI provider is responding at ${baseUrl}.`
          : undefined)
      }
      actions={
        onRecheck ? (
          <button
            type="button"
            className="secondary"
            onClick={onRecheck}
            disabled={rechecking}
            aria-busy={rechecking}
          >
            {rechecking ? 'Checking...' : 'Recheck AI status'}
          </button>
        ) : undefined
      }
    />
  );
}

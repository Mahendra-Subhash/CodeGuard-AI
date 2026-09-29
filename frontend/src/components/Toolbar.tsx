/*
 * ============================================================
 * TOOLBAR
 * ============================================================
 *
 * The primary actions. While an operation runs every button is disabled so a
 * second submission cannot be started by accident, and the running action says
 * what it is doing instead of looking unresponsive.
 */

import type { SampleSnippet } from '../data/sampleSnippets';
import { OPERATION_LABELS, type Operation } from '../ux/operation';

type Props = {
  samples: SampleSnippet[];
  selectedSample: string;
  onSelectSample: (label: string) => void;
  onScan: () => void;
  onRescan: () => void;
  onClear: () => void;
  onReset: () => void;
  hasScan: boolean;
  canScan: boolean;
  busy: Operation | null;
};

export default function Toolbar({
  samples,
  selectedSample,
  onSelectSample,
  onScan,
  onRescan,
  onClear,
  onReset,
  hasScan,
  canScan,
  busy,
}: Props) {

  const isBusy = busy !== null;

  return (
    <div
      className="toolbar"
      role="toolbar"
      aria-label="CodeGuard actions"
    >

      <label
        className="toolbar-field"
        htmlFor="sample-select"
      >
        Sample
      </label>

      <select
        id="sample-select"
        value={selectedSample}
        onChange={(event) => onSelectSample(event.target.value)}
        disabled={isBusy}
      >
        {samples.map((sample) => (
          <option
            key={sample.label}
            value={sample.label}
          >
            {sample.label}
          </option>
        ))}
      </select>

      <button
        type="button"
        className="scan"
        onClick={onScan}
        disabled={isBusy || !canScan}
        aria-busy={busy === 'scan'}
        title={
          canScan
            ? 'Analyze the code currently in the editor'
            : 'There is no Java code to scan'
        }
      >
        {busy === 'scan' ? OPERATION_LABELS.scan : 'Scan current code'}
      </button>

      {hasScan && (
        <button
          type="button"
          className="scan"
          onClick={onRescan}
          disabled={isBusy}
          aria-busy={busy === 'rescan'}
          title="Analyze the current editor contents again"
        >
          {busy === 'rescan'
          ? OPERATION_LABELS.rescan
          : 'Re-scan current code'}
        </button>
      )}

      <button
        type="button"
        className="secondary"
        onClick={onClear}
        disabled={isBusy}
      >
        Clear editor
      </button>

      <button
        type="button"
        className="secondary"
        onClick={onReset}
        disabled={isBusy}
      >
        Load sample again
      </button>

    </div>
  );
}

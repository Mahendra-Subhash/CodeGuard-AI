/*
 * ============================================================
 * FINDING LIST
 * ============================================================
 *
 * Each finding shows which analyzer detected it, the rule, the severity, the
 * location and the short message, so it is always clear that the static
 * analyzer found the issue and the AI only adds explanation.
 *
 * Findings are buttons, so they are reachable and selectable with the keyboard.
 */

import type { Finding } from '../api/types';

type Props = {
  findings: Finding[];
  selectedFindingId: string | null;
  onSelect: (findingId: string) => void;
  scanStatus: string | null;
  /** Findings the baseline contained before the current code was analyzed. */
  resolvedCount: number;
};

const ANALYZER_CLASS: Record<string, string> = {
  PMD: 'analyzer-pmd',
  Checkstyle: 'analyzer-checkstyle',
  SpotBugs: 'analyzer-spotbugs',
  'CodeGuard-Heuristics': 'analyzer-heuristics',
};

export default function FindingList({
  findings,
  selectedFindingId,
  onSelect,
  scanStatus,
  resolvedCount,
}: Props) {

  return (
    <aside
      className="card findings-panel"
      aria-label="Static analysis findings"
    >

      <h3>Findings</h3>

      <p className="muted findings-summary">
        {findings.length === 0
          ? 'No findings detected'
          : `${findings.length} finding${findings.length === 1 ? '' : 's'} detected`}
        {scanStatus ? ` · scan status: ${scanStatus}` : ''}
        {resolvedCount > 0 ? ` · ${resolvedCount} baseline finding(s) resolved` : ''}
      </p>

      {findings.length === 0 && (
        <div className="detail-block">

          <h4>No findings detected</h4>

          <p>
            The analyzed Java source does not contain findings for PMD,
            Checkstyle or SpotBugs.
          </p>

        </div>
      )}

      <ul className="findings-list">

        {findings.map((finding) => {

          const isSelected = finding.id === selectedFindingId;

          return (
            <li key={finding.id}>

              <button
                type="button"
                className={`finding-item ${isSelected ? 'selected' : ''}`}
                onClick={() => onSelect(finding.id)}
                aria-pressed={isSelected}
              >

                <span className="finding-header">

                  <strong>{finding.title}</strong>

                  <span
                    className={`severity ${finding.severity.toLowerCase()}`}
                  >
                    {finding.severity}
                  </span>

                </span>

                <span
                  className={`analyzer-tag ${ANALYZER_CLASS[finding.analyzer] ?? 'analyzer-other'}`}
                >
                  {finding.analyzer}
                </span>

                <span className="muted finding-meta">
                  Rule: {finding.rule}
                  {finding.category ? ` · Category: ${finding.category}` : ''}
                </span>

                <span className="muted finding-meta">
                  {finding.file}:{finding.line}:{finding.column}
                </span>

                <span className="finding-message">
                  {finding.message}
                </span>

                {finding.codeSnippet && (
                  <code className="finding-snippet">
                    {finding.codeSnippet}
                  </code>
                )}

              </button>

            </li>
          );
        })}

      </ul>

    </aside>
  );
}

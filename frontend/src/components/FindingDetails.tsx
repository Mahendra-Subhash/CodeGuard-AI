/*
 * ============================================================
 * FINDING DETAILS + AI ANALYSIS
 * ============================================================
 *
 * Makes the two roles explicit:
 *
 *   - the STATIC ANALYZER (PMD / Checkstyle / SpotBugs / CodeGuard-Heuristics)
 *     detected the issue and owns the finding metadata
 *   - the AI assistant explains it and proposes a change
 *
 * The AI response is organized into the sections the backend actually returned
 * ("Why this matters", "What is wrong", "Root cause", "Recommended fix") and the
 * reported confidence is shown as reported.
 */

import AiUnavailableNotice from './AiUnavailableNotice';
import StatusBanner from './StatusBanner';
import type { AnalysisResult, Finding } from '../api/types';
import { OPERATION_LABELS, type Operation } from '../ux/operation';
import {
  analysisSections,
  describeConfidence,
} from '../ux/aiState';

type Props = {
  finding: Finding;
  analysis: AnalysisResult | null;
  analysisDegraded: boolean;
  busy: Operation | null;
  aiOffline: boolean;
  aiBaseUrl?: string;
  aiModel?: string | null;
  aiRuntime?: string | null;
  onAnalyze: () => void;
  onProposeFix: () => void;
  onRecheckAi: () => void;
};

export default function FindingDetails({
  finding,
  analysis,
  analysisDegraded,
  busy,
  aiOffline,
  aiBaseUrl,
  aiModel,
  aiRuntime,
  onAnalyze,
  onProposeFix,
  onRecheckAi,
}: Props) {

  const sections = analysisSections(analysis);
  const confidence = describeConfidence(analysis);
  const aiSource =
    aiModel && aiRuntime
      ? `${aiModel} via ${aiRuntime} (local)`
      : 'the configured local AI provider';

  return (
    <section className="details-box space-top">

      <div className="detail-block">

        <h4>Finding details</h4>

        <p className="analyzer-attribution">
          Detected by static analysis: <strong>{finding.analyzer}</strong>
          {' '}· rule {finding.rule}
        </p>

        <strong>{finding.title}</strong>

        <p>{finding.message}</p>

        <div className="muted">
          Severity: {finding.severity}
          {finding.category ? ` · Category: ${finding.category}` : ''}
          {` · File: ${finding.file}`}
          {` · Line: ${finding.line}`}
          {` · Column: ${finding.column}`}
        </div>

      </div>

      <div className="detail-block">

        <h4>Affected code</h4>

        <pre className="code-block">
          {finding.codeSnippet || 'No snippet available'}
        </pre>

        {finding.suggestedContext && (
          <p className="muted">
            Analyzer context: {finding.suggestedContext}
          </p>
        )}

      </div>

      <div className="detail-block">

        <h4>AI analysis</h4>

        <p className="muted">
          The AI assistant explains this finding and proposes a change. It never
          edits your code directly; CodeGuard's safety layer decides whether a
          proposal can be applied.
        </p>

        <div className="button-row">

          <button
            type="button"
            className="secondary"
            onClick={onAnalyze}
            disabled={busy !== null}
            aria-busy={busy === 'analyze'}
            title={
              aiOffline
                ? 'The local AI provider is not responding'
                : 'Ask the local model to explain this finding'
            }
          >
            {busy === 'analyze'
              ? OPERATION_LABELS.analyze
              : 'Analyze with AI'}
          </button>

          <button
            type="button"
            className="secondary"
            onClick={onProposeFix}
            disabled={busy !== null}
            aria-busy={busy === 'propose-fix'}
            title={
              aiOffline
                ? 'The local AI provider is not responding'
                : 'Ask the local model for a fix proposal'
            }
          >
            {busy === 'propose-fix'
              ? OPERATION_LABELS['propose-fix']
              : 'Propose AI fix'}
          </button>

        </div>

        {aiOffline && (
          <div className="space-top">
            <AiUnavailableNotice
              baseUrl={aiBaseUrl}
              onRecheck={onRecheckAi}
              rechecking={busy === 'runtime'}
            />
          </div>
        )}

        {analysis && analysisDegraded && (
          <div className="space-top">
            <StatusBanner
              tone="warn"
              title="AI explanation unavailable"
              message="This is CodeGuard's fallback text, not model output. The local AI provider did not return an explanation for this finding."
              detail={analysis.summary}
            />
          </div>
        )}

        {analysis && !analysisDegraded && (
          <div className="space-top ai-response">

            <p className="muted ai-source">
              Explanation source: {aiSource}
            </p>

            {sections.map((section) => (
              <div
                key={section.title}
                className="ai-section"
              >
                <h5>{section.title}</h5>
                <p>{section.body}</p>
              </div>
            ))}

            {confidence && (
              <p className="confidence-line">
                <strong>Confidence:</strong> {confidence}
              </p>
            )}

            {analysis.correctedCode && (
              <>
                <h5>Suggested corrected code</h5>

                <pre className="code-block">
                  {analysis.correctedCode}
                </pre>
              </>
            )}

            {analysis.verificationHint && (
              <p className="muted">
                {analysis.verificationHint}
              </p>
            )}

          </div>
        )}

      </div>

    </section>
  );
}

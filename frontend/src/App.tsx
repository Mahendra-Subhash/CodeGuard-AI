import { useCallback, useEffect, useMemo, useState } from 'react';
import Editor from '@monaco-editor/react';

import {
  analyzeFinding,
  applyFix,
  createScan,
  describeRequestFailure,
  fetchRuntime,
  getScan,
  proposeFix,
  readConflict,
  rescanScan,
  verifyScan,
} from './api/client';
import type {
  AnalysisResult,
  FixConflict,
  FixProposal,
  RuntimeInfo,
  Scan,
  VerificationResult,
} from './api/types';
import {
  isDegradedAnalysis,
  isDegradedProposal,
  proposalConfidence,
} from './ux/aiState';
import { describeFailure } from './ux/errors';
import { describeFixOutcome, type FixOutcome } from './ux/fixOutcome';
import type { Operation } from './ux/operation';
import {
  buildRescanRequest,
  isAnalyzable,
  maySyncEditorFromResponse,
} from './ux/rescanSource';
import { describeRuntime } from './ux/runtimeStatus';
import { sampleSnippets } from './data/sampleSnippets';

import FixOutcomePanel from './components/FixOutcomePanel';
import FindingDetails from './components/FindingDetails';
import FindingList from './components/FindingList';
import ProposedFixPanel from './components/ProposedFixPanel';
import RuntimeStatusBar from './components/RuntimeStatusBar';
import StatusBanner, { type StatusTone } from './components/StatusBanner';
import Toolbar from './components/Toolbar';

type Notice = {
  tone: StatusTone;
  title: string;
  message: string;
  detail?: string;
};

const defaultCode = sampleSnippets[1]?.code ?? '';

function App() {

  /*
   * ============================================================
   * RUNTIME STATUS
   * ============================================================
   */

  const [runtime, setRuntime] = useState<RuntimeInfo | null>(null);
  const [runtimeLoading, setRuntimeLoading] = useState(false);

  /*
   * ============================================================
   * EDITOR / SCAN STATE
   * ============================================================
   *
   * `sourceCode` is the single source of truth for what gets analyzed: the
   * editor writes it, and every scan request sends it.
   */

  const [selectedSample, setSelectedSample] = useState(
    sampleSnippets[1]?.label ?? sampleSnippets[0]?.label ?? ''
  );

  const [sourceCode, setSourceCode] = useState(defaultCode);

  const [scan, setScan] = useState<Scan | null>(null);
  const [selectedFindingId, setSelectedFindingId] = useState<string | null>(null);
  const [scanCount, setScanCount] = useState(0);

  /*
   * ============================================================
   * AI / FIX STATE
   * ============================================================
   */

  const [analysis, setAnalysis] = useState<AnalysisResult | null>(null);
  const [fixProposal, setFixProposal] = useState<FixProposal | null>(null);
  const [verifyResult, setVerifyResult] = useState<VerificationResult | null>(null);

  /** Classified result of the last apply-fix attempt. */
  const [fixOutcome, setFixOutcome] = useState<FixOutcome | null>(null);

  /** The operation currently running, if any. Two never run at once. */
  const [operation, setOperation] = useState<Operation | null>(null);

  const [notice, setNotice] = useState<Notice | null>(null);

  const busy = operation !== null;

  const [fileName, setFileName] = useState(
    sampleSnippets[1]?.fileName ?? 'Sample.java'
  );

  const loadRuntime = useCallback(async () => {

    setRuntimeLoading(true);

    try {
      setRuntime(await fetchRuntime());
    } catch (error) {
      console.error('Runtime status failed:', error);
      setRuntime(null);
    } finally {
      setRuntimeLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadRuntime();
  }, [loadRuntime]);

  /**
   * Re-reads the runtime facts, e.g. after the developer started the local AI
   * server. The dashboard never guesses: it asks the backend again.
   */
  const handleRecheckRuntime = useCallback(async () => {

    if (operation !== null) {
      return;
    }

    setOperation('runtime');

    try {
      await loadRuntime();
    } finally {
      setOperation(null);
    }
  }, [operation, loadRuntime]);

  const runtimeSummary = useMemo(
    () => describeRuntime(runtime, runtimeLoading),
    [runtime, runtimeLoading]
  );

  /** True only when the backend probed the provider and it did not answer. */
  const aiOffline = runtimeSummary.aiAvailability === 'offline';

  /*
   * ============================================================
   * SELECTED FINDING / COUNTS
   * ============================================================
   */

  const selectedFinding = useMemo(() => {

    if (!scan) {
      return null;
    }

    if (selectedFindingId) {

      const match = scan.findings.find(
        (finding) => finding.id === selectedFindingId
      );

      if (match) {
        return match;
      }
    }

    return scan.findings[0] ?? null;
  }, [scan, selectedFindingId]);

  const findings = useMemo(
    () => scan?.findings ?? [],
    [scan]
  );

  const totalFindings = findings.length;

  const criticalCount = countSeverity(findings, 'CRITICAL');
  const highCount = countSeverity(findings, 'HIGH');
  const mediumCount = countSeverity(findings, 'MEDIUM');
  const lowCount = countSeverity(findings, 'LOW');

  /**
   * Baseline findings the current analysis no longer reports.
   */
  const resolvedBaselineCount = useMemo(() => {

    if (!scan?.baselineFindings) {
      return 0;
    }

    const currentIds = new Set(
      scan.findings.map((finding) => finding.id)
    );

    return scan.baselineFindings.filter(
      (finding) => !currentIds.has(finding.id)
    ).length;
  }, [scan]);

  const analysisDegraded = isDegradedAnalysis(analysis);
  const proposalDegraded = isDegradedProposal(fixProposal);

  /** Model reported confidence, shown next to the proposal it belongs to. */
  const proposalConfidenceLabel = proposalConfidence(analysis, fixProposal);

  const canScan = isAnalyzable(sourceCode);

  /**
   * Keeps the selection when that finding survived the new analysis, otherwise
   * selects the first finding of the new result.
   */
  const focusFinding = useCallback((updated: Scan) => {

    setSelectedFindingId((current) => {

      const stillPresent = updated.findings.some(
        (finding) => finding.id === current
      );

      return stillPresent ? current : updated.findings[0]?.id ?? null;
    });
  }, []);

  /*
   * ============================================================
   * SHARED HELPERS
   * ============================================================
   */

  const resetAiState = useCallback(() => {
    setAnalysis(null);
    setFixProposal(null);
    setVerifyResult(null);
    setFixOutcome(null);
  }, []);

  const reportFailure = useCallback((context: string, error: unknown) => {

    const description = describeFailure(
      describeRequestFailure(error),
      context
    );

    setNotice({
      tone: 'danger',
      title: description.title,
      message: description.message,
      detail: description.detail,
    });

    console.error(`${context} failed:`, error);
  }, []);

  /*
   * ============================================================
   * EDITOR ACTIONS
   * ============================================================
   */

  const loadSample = useCallback((label: string, title: string) => {

    const sample = sampleSnippets.find(
      (entry) => entry.label === label
    );

    if (!sample) {
      return;
    }

    setSelectedSample(label);
    setSourceCode(sample.code);
    setFileName(sample.fileName);
    setScan(null);
    setSelectedFindingId(null);
    resetAiState();

    setNotice({
      tone: 'neutral',
      title,
      message:
        'A scan always analyzes exactly what is in the editor, so you can keep editing before you scan.',
    });
  }, [resetAiState]);

  const handleSelectSample = useCallback((label: string) => {
    loadSample(label, 'Sample loaded');
  }, [loadSample]);

  const handleReloadSample = useCallback(() => {
    loadSample(selectedSample, 'Sample restored');
  }, [loadSample, selectedSample]);

  const handleClearEditor = useCallback(() => {

    setSourceCode('');

    setNotice({
      tone: 'neutral',
      title: 'Editor cleared',
      message: scan
        ? 'The findings below still refer to the source that was analyzed before.'
        : 'There is nothing to analyze until you write some Java code.',
    });
  }, [scan]);

  /*
   * ============================================================
   * SCAN
   * ============================================================
   *
   * The request always carries the current editor contents, so the analyzed
   * code is the code the developer is looking at.
   */

  const handleScan = useCallback(async () => {

    if (operation !== null) {
      return;
    }

    if (!isAnalyzable(sourceCode)) {

      setNotice({
        tone: 'warn',
        title: 'Nothing to scan',
        message: 'Write or paste some Java code in the editor first.',
      });

      return;
    }

    setOperation('scan');
    setNotice(null);
    resetAiState();

    try {

      const created = await createScan(sourceCode, fileName);

      setScan(created);
      setFileName(created.fileName ?? fileName);
      focusFinding(created);
      setScanCount((count) => count + 1);

      const found = created.findings?.length ?? 0;

      setNotice({
        tone: found === 0 ? 'success' : 'neutral',
        title: 'Scan complete',
        message: found === 0
          ? 'No findings in the code currently in the editor.'
          : `${found} finding${found === 1 ? '' : 's'} reported by the static analyzers.`,
        detail: `Analyzed the editor contents as ${created.fileName ?? fileName}.`,
      });

    } catch (error) {
      setScan(null);
      setSelectedFindingId(null);
      reportFailure('The scan', error);
    } finally {
      setOperation(null);
    }
  }, [
    fileName,
    focusFinding,
    operation,
    reportFailure,
    resetAiState,
    sourceCode,
  ]);

  /*
   * ============================================================
   * RE-SCAN
   * ============================================================
   *
   * The payload always carries the CURRENT editor contents, so the source
   * stored by the previous scan can never be analyzed instead. The editor is
   * deliberately not written back: it already holds what was sent.
   */

  const handleRescan = useCallback(async () => {

    if (operation !== null || !scan) {
      return;
    }

    if (!isAnalyzable(sourceCode)) {

      setNotice({
        tone: 'warn',
        title: 'Nothing to re-scan',
        message: 'The editor is empty, so there is no code to analyze again.',
      });

      return;
    }

    setOperation('rescan');
    setNotice(null);
    resetAiState();

    try {

      const payload = buildRescanRequest(sourceCode, scan);

      const updated = await rescanScan(
        scan.id,
        payload.sourceCode,
        payload.fileName
      );

      setScan(updated);
      focusFinding(updated);
      setScanCount((count) => count + 1);

      const found = updated.findings?.length ?? 0;
      const baseline = updated.baselineFindings?.length ?? 0;

      setNotice({
        tone: found === 0 ? 'success' : 'neutral',
        title: 'Re-scan complete',
        message: found === 0
          ? 'The current editor contents are clean.'
          : `${found} finding${found === 1 ? '' : 's'} still reported.`,
        detail: baseline > 0
          ? `Compared against the baseline of ${baseline} finding(s) kept from the first scan.`
          : undefined,
      });

    } catch (error) {
      reportFailure('The re-scan', error);
    } finally {
      setOperation(null);
    }
  }, [
    focusFinding,
    operation,
    reportFailure,
    resetAiState,
    scan,
    sourceCode,
  ]);

  /*
   * ============================================================
   * AI ANALYSIS / FIX PROPOSAL
   * ============================================================
   *
   * Both requests can answer HTTP 200 with a degraded body when the local model
   * did not reply. That degraded state is detected in the components, so no
   * fallback text is ever presented as model output.
   */

  const handleAnalyze = useCallback(async () => {

    if (operation !== null || !selectedFinding) {
      return;
    }

    setOperation('analyze');
    setNotice(null);
    setAnalysis(null);

    try {
      setAnalysis(await analyzeFinding(selectedFinding.id));
    } catch (error) {
      reportFailure('The AI analysis', error);
    } finally {
      setOperation(null);
    }
  }, [operation, reportFailure, selectedFinding]);

  const handleProposeFix = useCallback(async () => {

    if (operation !== null || !selectedFinding) {
      return;
    }

    setOperation('propose-fix');
    setNotice(null);
    setFixProposal(null);
    setFixOutcome(null);

    try {
      setFixProposal(await proposeFix(selectedFinding.id));
    } catch (error) {
      reportFailure('The fix proposal', error);
    } finally {
      setOperation(null);
    }
  }, [operation, reportFailure, selectedFinding]);

  const handleRejectProposal = useCallback(() => {
    setFixProposal(null);
    setFixOutcome(null);
  }, []);

  /*
   * ============================================================
   * APPLY FIX
   * ============================================================
   *
   * The AI only ever proposes. Whether a proposal may be written is decided by
   * the backend safety layer, and the outcome is classified in `ux/fixOutcome`
   * so "applied", "already applied", "requires review" and "failed" stay
   * distinguishable instead of collapsing into one generic error.
   */

  const handleApplyFix = useCallback(async () => {

    if (operation !== null || !selectedFinding || !fixProposal) {
      return;
    }

    const proposedCode = fixProposal.proposedCode;

    if (!proposedCode || proposedCode.trim().length === 0) {

      setFixOutcome(
        describeFixOutcome({
          type: 'rejected',
          conflict: {
            status: 'requires-review',
            error:
              'The proposal did not contain any code, so there was nothing to apply.',
          },
        })
      );

      return;
    }

    /**
     * The source the backend holds for this scan: apply-fix rewrites that
     * source, so it is the baseline the outcome is measured against.
     */
    const previousSourceCode = scan?.sourceCode ?? '';
    const appliedFindingId = selectedFinding.id;

    setOperation('apply-fix');
    setNotice(null);
    setFixOutcome(null);

    try {

      const updated = await applyFix(appliedFindingId, proposedCode);

      const outcome = describeFixOutcome({
        type: 'response',
        previousSourceCode,
        updatedScan: updated,
      });

      setScan(updated);
      setFixProposal(null);
      setAnalysis(null);
      setVerifyResult(null);
      setFixOutcome(outcome);

      /*
       * Move to the next finding when the fixed one disappeared, otherwise keep
       * it visible so the developer can re-analyze it.
       */
      const others = updated.findings.filter(
        (finding) => finding.id !== appliedFindingId
      );

      setSelectedFindingId(others[0]?.id ?? updated.findings[0]?.id ?? null);

      /*
       * apply-fix is the only operation allowed to write the returned source
       * back into the editor: the safety layer really rewrote that file.
       */
      if (
        maySyncEditorFromResponse('apply-fix') &&
        typeof updated.sourceCode === 'string'
      ) {
        setSourceCode(updated.sourceCode);
      }

    } catch (error) {

      const outcome = describeFixOutcome({
        type: 'failure',
        failure: describeRequestFailure(error),
        conflict: readConflict(error) as FixConflict,
      });

      setFixOutcome(outcome);

      /*
       * A refused proposal is information, not a defect: only real failures are
       * logged.
       */
      if (
        outcome.kind !== 'requires-review' &&
        outcome.kind !== 'already-applied'
      ) {
        console.error('Applying the fix failed:', error);
      }
    } finally {
      setOperation(null);
    }
  }, [
    fixProposal,
    operation,
    scan,
    selectedFinding,
  ]);

  /*
   * ============================================================
   * VERIFY
   * ============================================================
   *
   * Compares the current stored source against the baseline kept by the backend.
   * The editor is not touched: verifying never rewrites code.
   */

  const handleVerify = useCallback(async () => {

    if (operation !== null || !scan) {
      return;
    }

    setOperation('verify');
    setNotice(null);

    try {

      const result = await verifyScan(scan.id);

      setVerifyResult(result);

      const fresh = await getScan(scan.id);
      setScan(fresh);

      const resolved = result.resolved?.length ?? 0;
      const stillPresent = result.stillPresent?.length ?? 0;
      const introduced = result.newFindings?.length ?? 0;

      setNotice({
        tone: result.verified ? 'success' : 'warn',
        title: result.verified
          ? 'Verification passed'
          : 'Verification reports remaining findings',
        message: result.verified
          ? `${resolved} baseline finding(s) are gone and the re-analysis introduced no new ones.`
          : `${stillPresent} baseline finding(s) are still present, ${introduced} new finding(s) appeared.`,
      });

    } catch (error) {
      reportFailure('The verification', error);
    } finally {
      setOperation(null);
    }
  }, [operation, reportFailure, scan]);

  const dismissNotice = useCallback(() => setNotice(null), []);
  const dismissOutcome = useCallback(() => setFixOutcome(null), []);

  const aiRow = runtimeSummary.rows.find(
    (row) => row.label === 'AI Provider'
  );

  return (
    <main className="app">

      <header className="app-header">

        <div>

          <h1>CodeGuard-AI</h1>

          <p className="subtitle">
            Static analysis finds the issue. A local AI explains it. CodeGuard's
            safety layer decides whether a fix may be written.
          </p>

        </div>

        <div className="header-pills">

          <span className={`pill ${aiRow?.tone ?? 'neutral'}`}>
            AI provider: {aiRow?.value ?? 'Unknown'}
          </span>

          <span className="pill neutral">
            Qualcomm acceleration: not verified
          </span>

        </div>

      </header>

      {notice && (
        <div className="space-top">
          <StatusBanner
            tone={notice.tone}
            title={notice.title}
            message={notice.message}
            detail={notice.detail}
            onDismiss={dismissNotice}
          />
        </div>
      )}

      <section className="metrics">

        <div className="card metric">

          <span className="metric-label">CodeGuard</span>

          <strong>Active</strong>

        </div>

        <div className="card metric">

          <span className="metric-label">Analyses this session</span>

          <strong>{scanCount}</strong>

        </div>

        <div className="card metric">

          <span className="metric-label">Findings</span>

          <strong>{totalFindings}</strong>

        </div>

        <div className="card metric">

          <span className="metric-label">Critical / High</span>

          <strong>
            {criticalCount} / {highCount}
          </strong>

        </div>

        <div className="card metric">

          <span className="metric-label">Medium / Low</span>

          <strong>
            {mediumCount} / {lowCount}
          </strong>

        </div>

      </section>

      <div className="space-top">

        <RuntimeStatusBar
          runtime={runtime}
          summary={runtimeSummary}
          loading={runtimeLoading || operation === 'runtime'}
          onRecheck={() => void handleRecheckRuntime()}
        />

      </div>

      <div className="space-top">

        <Toolbar
          samples={sampleSnippets}
          selectedSample={selectedSample}
          onSelectSample={handleSelectSample}
          onScan={() => void handleScan()}
          onRescan={() => void handleRescan()}
          onClear={handleClearEditor}
          onReset={handleReloadSample}
          hasScan={scan !== null}
          canScan={canScan}
          busy={operation}
        />

      </div>

      <div className="workspace space-top">

        <section className="card editor-panel">

          <h3>Source under analysis</h3>

          <p className="muted editor-note">
            What you see here is what gets analyzed: every scan and re-scan sends
            these contents, and the file name is metadata only
            ({fileName}). Fixing, scanning and verifying never overwrite your
            edits — the only exception is a fix you apply yourself.
          </p>

          <Editor
            height="520px"
            language="java"
            theme="vs-dark"
            value={sourceCode}
            onChange={(value) => setSourceCode(value ?? '')}
            options={{
              minimap: { enabled: false },
              fontSize: 13,
              scrollBeyondLastLine: false,
              automaticLayout: true,
              ariaLabel: 'Java source editor',
            }}
          />

        </section>

        <FindingList
          findings={findings}
          selectedFindingId={selectedFinding?.id ?? null}
          onSelect={setSelectedFindingId}
          scanStatus={scan?.status ?? null}
          resolvedCount={resolvedBaselineCount}
        />

      </div>

      <div className="details-column space-top">

        {selectedFinding && (
          <FindingDetails
            finding={selectedFinding}
            analysis={analysis}
            analysisDegraded={analysisDegraded}
            busy={operation}
            aiOffline={aiOffline}
            aiBaseUrl={runtime?.aiBaseUrl}
            aiModel={runtimeSummary.model}
            aiRuntime={runtime?.runtime ?? null}
            onAnalyze={() => void handleAnalyze()}
            onProposeFix={() => void handleProposeFix()}
            onRecheckAi={() => void handleRecheckRuntime()}
          />
        )}

        {!selectedFinding && (
          <section className="card">

            <h3>Finding details</h3>

            <p className="muted">
              Run a scan, then select a finding to see which analyzer reported it
              and to ask the local AI for an explanation or a fix proposal.
            </p>

          </section>
        )}

        {selectedFinding && fixProposal && (
          <ProposedFixPanel
            proposal={fixProposal}
            degraded={proposalDegraded}
            confidence={proposalConfidenceLabel}
            busy={operation}
            onApply={() => void handleApplyFix()}
            onReject={handleRejectProposal}
          />
        )}

        {fixOutcome && (
          <FixOutcomePanel
            outcome={fixOutcome}
            onDismiss={dismissOutcome}
          />
        )}

        {scan && (
          <section className="card space-top">

            <h3>Verification</h3>

            <p className="muted">
              Compares the source stored for this scan against the baseline kept
              from its first analysis. If you edited the code in the editor, run
              a re-scan first so the comparison uses your current code.
            </p>

            <div className="button-row">

              <button
                type="button"
                className="secondary"
                onClick={() => void handleVerify()}
                disabled={operation !== null}
                aria-busy={operation === 'verify'}
              >
                {operation === 'verify'
                  ? 'Verifying...'
                  : 'Verify against baseline'}
              </button>

            </div>

            {verifyResult && (
              <div className="detail-block">

                <p className={verifyResult.verified ? 'verify-pass' : 'verify-warn'}>
                  {verifyResult.verified
                    ? 'Verified: the fixed findings are gone and no new finding appeared.'
                    : 'Not verified yet: some findings are still reported.'}
                </p>

                <p className="muted">
                  Baseline: {verifyResult.baselineFindingCount} finding(s)
                  {' · '}
                  Now: {verifyResult.currentFindingCount} finding(s)
                </p>

                <p className="muted">
                  Resolved: {verifyResult.resolved?.length ?? 0}
                  {' · '}
                  Still present: {verifyResult.stillPresent?.length ?? 0}
                  {' · '}
                  New: {verifyResult.newFindings?.length ?? 0}
                </p>

              </div>
            )}

          </section>
        )}

        <section className="card space-top">

          <h3>How this loop works</h3>

          <ol className="loop-steps">

            <li>Scan or re-scan sends the code in the editor.</li>

            <li>PMD, Checkstyle and SpotBugs report the findings.</li>

            <li>The local AI explains a selected finding and proposes a change.</li>

            <li>
              CodeGuard verifies the proposal against the current source before
              it may write anything — otherwise nothing is changed.
            </li>

            <li>Re-scan or verify to confirm, then repeat.</li>

          </ol>

          <p className="muted">
            No source code or finding detail is ever sent to an external AI
            service. Qualcomm NPU execution is not verified in this build, so it
            is always reported as not verified.
          </p>

        </section>

      </div>

      <footer className="footer space-top">
        Local static analysis · local AI explanation · verified fixes only
      </footer>

    </main>
  );

}

function countSeverity(
  findings: { severity: string }[],
  severity: string
): number {

  return findings.filter(
    (finding) => finding.severity.toUpperCase() === severity
  ).length;
}

export default App;

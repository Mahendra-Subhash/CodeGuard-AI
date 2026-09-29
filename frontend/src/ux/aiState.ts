/*
 * ============================================================
 * AI RESPONSE STATE
 * ============================================================
 *
 * The backend answers with HTTP 200 even when the local model could not be
 * reached: `HttpLocalAiInferenceProvider` then returns a degraded response
 * built by `buildDegradedResponse(...)`.
 *
 * The dashboard must never present those fallback texts as if the model had
 * produced them, so the degraded signature is detected here and surfaced
 * explicitly. The markers below mirror the backend implementation and are
 * covered by backend tests:
 *
 *  - degraded responses always report confidence "uncalibrated"
 *  - their summary is one of the fixed degraded summaries
 *  - a degraded fix proposal cannot change anything, so `proposedCode` equals
 *    `originalCode` and the diff is "No modification proposed"
 */

import type { AnalysisResult, FixProposal } from '../api/types';

export const UNCALIBRATED = 'uncalibrated';

const DEGRADED_SUMMARIES = [
  'local ai server unavailable or timed out',
  'local ai server returned error status',
  'local ai inference produced invalid json output',
  'security validation blocked external connection',
  'unexpected error during local ai inference',
];

const DEGRADED_RATIONALES = [
  'inspect the flagged code snippet and apply manual correction.',
  'local inference service was unable to generate an explanation.',
];

export function isDegradedAnalysis(
  analysis: AnalysisResult | null
): boolean {

  if (!analysis) {
    return false;
  }

  if (normalize(analysis.confidence) === UNCALIBRATED) {
    return true;
  }

  const summary = normalize(analysis.summary);

  return DEGRADED_SUMMARIES.some((marker) =>
    summary.startsWith(marker)
  );
}

export function isDegradedProposal(
  proposal: FixProposal | null
): boolean {

  if (!proposal) {
    return false;
  }

  const rationale = normalize(proposal.rationale);

  if (DEGRADED_RATIONALES.some((marker) => rationale.includes(marker))) {
    return true;
  }

  if (normalize(proposal.diff) === 'no modification proposed') {
    return true;
  }

  const originalCode = proposal.originalCode;
  const proposedCode = proposal.proposedCode;

  if (!originalCode || !proposedCode) {
    return false;
  }

  return normalize(originalCode) === normalize(proposedCode);
}

export type AnalysisSection = {
  /** "Why this matters" / "What is wrong" / ... */
  title: string;
  body: string;
};

/**
 * Organized view of the fields the backend actually returned. Sections whose
 * data is missing are omitted: nothing is invented and no empty heading is
 * rendered.
 */
export function analysisSections(
  analysis: AnalysisResult | null
): AnalysisSection[] {

  if (!analysis) {
    return [];
  }

  const sections: AnalysisSection[] = [];

  if (present(analysis.summary)) {
    sections.push({
      title: 'Summary',
      body: analysis.summary,
    });
  }

  if (present(analysis.risk)) {
    sections.push({
      title: 'Why this matters',
      body: analysis.risk,
    });
  }

  if (present(analysis.explanation)) {
    sections.push({
      title: 'What is wrong',
      body: analysis.explanation,
    });
  }

  if (present(analysis.rootCause)) {
    sections.push({
      title: 'Root cause',
      body: analysis.rootCause,
    });
  }

  if (present(analysis.recommendedFix)) {
    sections.push({
      title: 'Recommended fix',
      body: analysis.recommendedFix,
    });
  }

  return sections;
}

/**
 * Confidence is a model reported value. It is shown as reported, and
 * "uncalibrated" is explained instead of being dressed up as a percentage.
 */
export function describeConfidence(
  analysis: AnalysisResult | null
): string | null {

  if (!analysis || !present(analysis.confidence)) {
    return null;
  }

  const confidence = analysis.confidence.trim();

  if (confidence.toLowerCase() === UNCALIBRATED) {
    return 'Not calibrated (the model did not report a usable confidence)';
  }

  return confidence;
}

/**
 * Confidence belongs to the explanation, not to the patch. The dashboard shows
 * the model's reported confidence next to the proposal so both stay connected,
 * and shows nothing when either answer was a degraded fallback.
 */
export function proposalConfidence(
  analysis: AnalysisResult | null,
  proposal: FixProposal | null
): string | null {

  if (!proposal) {
    return null;
  }

  if (isDegradedProposal(proposal) || isDegradedAnalysis(analysis)) {
    return null;
  }

  return describeConfidence(analysis);
}

function present(value: string | undefined): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function normalize(value: string | undefined): string {
  return (value ?? '').replace(/\s+/g, ' ').trim().toLowerCase();
}

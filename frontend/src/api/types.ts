/*
 * ============================================================
 * BACKEND CONTRACT TYPES
 * ============================================================
 *
 * These types mirror what the Spring Boot backend actually returns.
 * Every field that the backend may omit (spring.jackson
 * default-property-inclusion: non_null) is marked optional, and no field is
 * invented here: the dashboard only ever displays data the backend sent.
 */

export type Finding = {
  id: string;
  analyzer: string;
  rule: string;
  category: string;
  severity: string;
  title: string;
  message: string;
  file: string;
  line: number;
  column: number;
  codeSnippet: string;
  suggestedContext: string;
};

export type Scan = {
  id: number;
  fileName: string;
  sourceCode: string;
  findings: Finding[];
  baselineFindings?: Finding[];
  status: string;
  createdAt: string;
  updatedAt: string;
};

export type RuntimeInfo = {
  provider?: string;
  model?: string;
  runtime?: string;
  timeoutMs?: number;
  contextLength?: number;
  privacyMode?: boolean;
  aiConfigured?: boolean;
  aiBaseUrl?: string;
  aiProviderReachable?: boolean;
  aiModelServed?: boolean;
  /** "responding" | "unreachable" | "skipped" */
  aiStatus?: string;
  aiServedModels?: string[];
  aiDetails?: string;
  qualcommAdapter?: string;
  qualcommDeploymentMode?: string;
  qualcommExecutionTarget?: string;
  qualcommAvailable?: boolean;
  qualcommNpuVerified?: boolean;
  qualcommDetails?: string;
  /** Observed presence of a local geniex launcher. Informational only. */
  qualcommGeniexCliDetected?: boolean;
  qualcommGeniexCliLocations?: string[];
  /**
   * How far verification got: "npu-verified", "server-reachable",
   * "no-evidence", "configuration-only" or "disabled".
   */
  qualcommVerificationTier?: string;
  /** One reason code per gate; entries starting with "gate-passed:" passed. */
  qualcommVerificationReasons?: string[];
  qualcommVerificationMessage?: string;
  qualcommVerificationEvidence?: {
    serverBaseUrl?: string;
    serverReachable?: boolean;
    modelServed?: boolean;
    servedModels?: string[];
    inferenceAttempted?: boolean;
    inferenceVerified?: boolean;
    inferenceLatencyMs?: number;
    generatedTokens?: number;
    artifactPresent?: boolean;
    artifactPath?: string;
    computeUnit?: string;
    plugin?: string;
    artifactModel?: string;
    chipset?: string;
    artifactHost?: string;
    artifactCapturedAt?: string;
    artifactAgeDays?: number;
    benchStatistics?: Record<string, number>;
    remoteDeviceEvidence?: boolean;
  };
};

export type AnalysisResult = {
  summary?: string;
  explanation?: string;
  rootCause?: string;
  risk?: string;
  recommendedFix?: string;
  correctedCode?: string;
  confidence?: string;
  verificationHint?: string;
};

export type FixProposal = {
  originalCode?: string;
  proposedCode?: string;
  diff?: string;
  rationale?: string;
};

export type VerificationResult = {
  verified: boolean;
  baselineFindingCount: number;
  currentFindingCount: number;
  resolved: string[];
  stillPresent: string[];
  newFindings: string[];
};

/**
 * Body of the 409 answer returned when a proposed fix could not be verified
 * against the current source. The source was NOT modified.
 */
export type FixConflict = {
  status?: string;
  error?: string;
  findingId?: string;
  startLine?: number;
  endLine?: number;
  sourceModified?: boolean;
};

/**
 * Transport level facts about a failed request. The user facing wording is
 * derived from this in `ux/errors.ts`, which keeps that wording unit testable
 * without a running backend.
 */
export type ApiFailureDescriptor = {
  networkError: boolean;
  status?: number;
  backendMessage?: string;
};

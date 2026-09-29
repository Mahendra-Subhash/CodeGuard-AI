/*
 * ============================================================
 * BACKEND CLIENT
 * ============================================================
 *
 * Every request the dashboard makes goes through this module so that:
 *
 *  - the endpoint shapes stay in exactly one place, and
 *  - failures are normalized into plain data (`ApiFailureDescriptor`) that the
 *    UX layer can turn into a truthful message without ever showing a stack
 *    trace or an internal implementation detail.
 */

import axios from 'axios';

import type {
  AnalysisResult,
  ApiFailureDescriptor,
  FixProposal,
  RuntimeInfo,
  Scan,
  VerificationResult,
} from './types';

const http = axios.create({
  headers: { 'Content-Type': 'application/json' },
});

export async function fetchRuntime(): Promise<RuntimeInfo> {
  return (await http.get<RuntimeInfo>('/api/runtime')).data;
}

export async function createScan(
  sourceCode: string,
  fileName: string
): Promise<Scan> {
  return (
    await http.post<Scan>('/api/scans', {
      sourceCode,
      fileName,
    })
  ).data;
}

/**
 * Re-scans a scan against the CURRENT editor contents.
 *
 * The body is what makes the re-scan analyze the code the developer has open
 * right now instead of the source stored by the previous scan.
 */
export async function rescanScan(
  scanId: number,
  sourceCode: string,
  fileName: string
): Promise<Scan> {
  return (
    await http.post<Scan>(`/api/scans/${scanId}/rescan`, {
      sourceCode,
      fileName,
    })
  ).data;
}

export async function getScan(scanId: number): Promise<Scan> {
  return (await http.get<Scan>(`/api/scans/${scanId}`)).data;
}

export async function analyzeFinding(
  findingId: string
): Promise<AnalysisResult> {
  return (
    await http.post<AnalysisResult>(`/api/findings/${findingId}/analyze`)
  ).data;
}

export async function proposeFix(findingId: string): Promise<FixProposal> {
  return (
    await http.post<FixProposal>(`/api/findings/${findingId}/propose-fix`)
  ).data;
}

export async function applyFix(
  findingId: string,
  correctedCode: string
): Promise<Scan> {
  return (
    await http.post<Scan>(`/api/findings/${findingId}/apply-fix`, {
      correctedCode,
    })
  ).data;
}

export async function verifyScan(scanId: number): Promise<VerificationResult> {
  return (await http.post<VerificationResult>(`/api/scans/${scanId}/verify`))
    .data;
}

/**
 * Turns anything axios can throw into transport facts the UX layer understands.
 * No user facing wording is produced here.
 */
export function describeRequestFailure(error: unknown): ApiFailureDescriptor {
  if (!axios.isAxiosError(error)) {
    return { networkError: false };
  }

  const response = error.response;

  if (!response) {
    return { networkError: true };
  }

  const body = response.data as { error?: unknown } | undefined;
  const backendMessage =
    body && typeof body.error === 'string' ? body.error : undefined;

  return {
    networkError: false,
    status: response.status,
    backendMessage,
  };
}

/**
 * The 409 body returned by the apply-fix endpoint. Returned as plain data so the
 * caller can react to `status` and `sourceModified` without any guessing.
 */
export function readConflict(error: unknown): Record<string, unknown> {
  if (!axios.isAxiosError(error) || !error.response) {
    return {};
  }

  const body = error.response.data;

  if (!body || typeof body !== 'object') {
    return {};
  }

  return body as Record<string, unknown>;
}

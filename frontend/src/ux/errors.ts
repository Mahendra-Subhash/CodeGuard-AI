/*
 * ============================================================
 * USER FACING ERROR DESCRIPTIONS
 * ============================================================
 *
 * Turns transport facts into a message a developer can act on.
 *
 * Rules:
 *  - never expose a Java stack trace or an internal class name
 *  - distinguish network failure from a backend failure
 *  - reuse the backend's own message when it sent one (those messages are
 *    written for humans, e.g. "Finding not found: abc123")
 */

import type { ApiFailureDescriptor } from '../api/types';

export type FailureKind =
  | 'network'
  | 'not-found'
  | 'validation'
  | 'server'
  | 'unknown';

export type FailureDescription = {
  kind: FailureKind;
  title: string;
  message: string;
  detail?: string;
};

export function describeFailure(
  failure: ApiFailureDescriptor,
  context: string
): FailureDescription {
  if (failure.networkError) {
    return {
      kind: 'network',
      title: 'Cannot reach the CodeGuard backend',
      message: `${context} did not complete because the backend on port 8080 is not responding. Start the backend and try again.`,
    };
  }

  const backendMessage = cleanMessage(failure.backendMessage);

  if (failure.status === 404) {
    return {
      kind: 'not-found',
      title: 'The backend does not know this item any more',
      message:
        backendMessage ??
        `${context} failed because the scan or finding is no longer available. Run a scan again.`,
      detail: backendMessage ? 'The backend reported HTTP 404.' : undefined,
    };
  }

  if (failure.status === 400) {
    return {
      kind: 'validation',
      title: 'The backend rejected the request',
      message:
        backendMessage ??
        `${context} was rejected because the request was not valid.`,
    };
  }

  if (failure.status != null && failure.status >= 500) {
    return {
      kind: 'server',
      title: 'The backend failed to complete the operation',
      message: `${context} failed for a server-side reason (HTTP ${failure.status}). Nothing in your editor was changed.`,
      detail: backendMessage,
    };
  }

  if (failure.status != null) {
    return {
      kind: 'server',
      title: 'The operation failed',
      message: `${context} failed with HTTP ${failure.status}.`,
      detail: backendMessage,
    };
  }

  return {
    kind: 'unknown',
    title: 'The operation failed',
    message: `${context} failed. Check the backend log for the exact cause.`,
  };
}

/**
 * The backend's own messages are already developer facing, but they must stay a
 * single line: no stack traces, no class names.
 */
function cleanMessage(message: string | undefined): string | undefined {
  if (!message || message.trim().length === 0) {
    return undefined;
  }

  const singleLine = message.replace(/\s+/g, ' ').trim();

  return singleLine;
}

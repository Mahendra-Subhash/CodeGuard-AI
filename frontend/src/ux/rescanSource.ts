/*
 * ============================================================
 * RE-SCAN SOURCE POLICY
 * ============================================================
 *
 * The editor is the source of truth for a re-scan.
 *
 * Requirement: "Re-scan current code" must analyze what the developer has in
 * the editor right now, never the source stored by the previous scan, and it
 * must not overwrite what the developer typed.
 */

import type { Scan } from '../api/types';
import type { Operation } from './operation';

export type RescanRequestPayload = {
  sourceCode: string;
  fileName: string;
};

/**
 * Builds the re-scan payload from the CURRENT editor contents.
 *
 * `storedSource` may not be sent: the backend keeps the previous source only as
 * a fallback for callers that send no body at all.
 */
export function buildRescanRequest(
  editorSource: string,
  scan: Scan | null
): RescanRequestPayload {
  return {
    sourceCode: editorSource,
    fileName: resolveFileName(scan),
  };
}

/**
 * The file name shown by the analyzers. It is metadata only; the analyzed
 * source always comes from the editor.
 */
export function resolveFileName(scan: Scan | null): string {
  const fileName = scan?.fileName;

  if (fileName && fileName.trim().length > 0) {
    return fileName;
  }

  return 'Sample.java';
}

/**
 * Which operation is allowed to write the response source back into the editor.
 *
 * - apply-fix: YES. The backend really rewrote the stored source through the
 *   verified line range, so the editor has to show the new file.
 * - scan / rescan: NO. The editor already holds the source that was sent; syncing
 *   could only replace unsaved edits with an equal or older copy.
 */
export function maySyncEditorFromResponse(
  operation: Extract<Operation, 'scan' | 'rescan' | 'apply-fix'>
): boolean {
  return operation === 'apply-fix';
}

/**
 * True when the given source may be sent for analysis.
 */
export function isAnalyzable(sourceCode: string): boolean {
  return sourceCode.trim().length > 0;
}

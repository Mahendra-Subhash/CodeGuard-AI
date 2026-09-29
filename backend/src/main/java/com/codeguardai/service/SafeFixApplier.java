package com.codeguardai.service;

import com.codeguardai.domain.Finding;
import com.codeguardai.domain.FixApplicationResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Applies an AI proposed correction to a single, verified line range.
 *
 * Safety rules enforced here:
 *
 * 1. The model output is treated as a FRAGMENT for the finding's line. It is
 *    never written over the whole file.
 * 2. The target range is proven by the finding's own metadata
 *    (Finding.line plus Finding.codeSnippet) before anything is written.
 * 3. The complete range the correction would overwrite is validated, not just
 *    its first line. A correction spanning more lines than the finding proves
 *    is refused, so unverified source lines are never overwritten.
 * 4. A range that reaches past the end of the scanned source is refused.
 * 5. When the range cannot be confirmed exactly - missing or invalid line,
 *    missing snippet, or a source that no longer matches the finding snippet -
 *    the source code is returned unchanged with the requires-review status.
 * 6. Repeating the same correction is detected and reported as already applied
 *    instead of duplicating code.
 * 7. The patch is atomic: the source is only rebuilt after every affected line
 *    has been validated, and a refusal returns the original source unchanged.
 */
@Service
public class SafeFixApplier {

    public PatchResult apply(
            String sourceCode,
            Finding finding,
            String correctedCode
    ) {

        if (sourceCode == null || sourceCode.isBlank()) {
            return refused(
                    "The scanned source code is unavailable, so the target line range cannot be confirmed."
                            + " The fix was not applied.",
                    0,
                    0,
                    sourceCode
            );
        }

        if (correctedCode == null || correctedCode.isBlank()) {
            return refused(
                    "The proposed correction does not contain any code, so nothing was written.",
                    0,
                    0,
                    sourceCode
            );
        }

        if (finding == null) {
            return refused(
                    "The finding metadata is missing, so the target line range is unknown."
                            + " The fix was not applied.",
                    0,
                    0,
                    sourceCode
            );
        }

        String newline =
                sourceCode.contains("\r\n")
                        ? "\r\n"
                        : "\n";

        List<String> sourceLines =
                new ArrayList<>(
                        Arrays.asList(
                                sourceCode.split("\r?\n", -1)
                        )
                );

        int line =
                finding.getLine();

        if (line < 1 || line > sourceLines.size()) {
            return refused(
                    "The finding line (" + line + ") is outside the scanned source (1-"
                            + sourceLines.size() + "), so the fix was not applied.",
                    0,
                    0,
                    sourceCode
            );
        }

        List<String> replacementLines =
                toLines(
                        correctedCode
                );

        if (replacementLines.isEmpty()) {
            return refused(
                    "The proposed correction does not contain any source line, so nothing was written.",
                    line,
                    line,
                    sourceCode
            );
        }

        int endLine =
                line + replacementLines.size() - 1;

        /*
         * The affected range must lie completely inside the scanned source.
         * A correction that reaches past the last line can never be validated,
         * so nothing is written.
         */
        if (endLine > sourceLines.size()) {
            return refused(
                    "The proposed correction would replace lines " + line + "-" + endLine
                            + " but the scanned source only has " + sourceLines.size()
                            + " line(s), so the fix was not applied. Review the change manually.",
                    line,
                    endLine,
                    sourceCode
            );
        }

        if (matchesExistingBlock(
                sourceLines,
                line,
                replacementLines
        )) {
            return new PatchResult(
                    false,
                    FixApplicationResult.STATUS_ALREADY_APPLIED,
                    "Line " + line + " already contains the proposed correction, so the source was left unchanged.",
                    line,
                    endLine,
                    sourceCode
            );
        }

        /*
         * The lines the correction overwrites are proven by the finding
         * metadata. A correction is only written when its own line count
         * matches the number of lines the finding proves, so a single
         * validated anchor can never authorise unverified extra lines.
         */
        List<String> expectedLines =
                toLines(
                        finding.getCodeSnippet()
                );

        if (expectedLines.isEmpty()) {
            return refused(
                    "The finding has no code snippet, so line " + line
                            + " cannot be confirmed and the fix was not applied.",
                    line,
                    line,
                    sourceCode
            );
        }

        if (expectedLines.size() != replacementLines.size()) {
            return refused(
                    "The proposed correction spans " + replacementLines.size()
                            + " line(s) but the finding only proves " + expectedLines.size()
                            + " line(s) starting at line " + line
                            + ", so the complete range could not be validated and the fix was not"
                            + " applied. Review the change manually.",
                    line,
                    endLine,
                    sourceCode
            );
        }

        for (int offset = 0; offset < replacementLines.size(); offset++) {

            int targetLine =
                    line + offset;

            String expected =
                    normalize(
                            expectedLines.get(offset)
                    );

            String actual =
                    normalize(
                            sourceLines.get(targetLine - 1)
                    );

            if (!expected.equals(actual)) {
                return refused(
                        "The source at line " + targetLine
                                + " no longer matches the finding snippet, so the fix was not applied."
                                + " Review the change manually."
                                + " Expected snippet: [" + expected + "]."
                                + " Found: [" + actual + "].",
                        line,
                        endLine,
                        sourceCode
                );
            }
        }

        List<String> patched =
                new ArrayList<>(
                        sourceLines.subList(
                                0,
                                line - 1
                        )
                );

        patched.addAll(
                replacementLines
        );

        patched.addAll(
                sourceLines.subList(
                        line,
                        sourceLines.size()
                )
        );

        return new PatchResult(
                true,
                FixApplicationResult.STATUS_APPLIED,
                "Applied the proposed correction to line " + line + " ("
                        + replacementLines.size() + " line(s) written)."
                        + " Every unrelated line was preserved.",
                line,
                endLine,
                String.join(
                        newline,
                        patched
                )
        );
    }


    /**
     * True when the source already contains the proposed block at the exact
     * matched position. The comparison ignores indentation only.
     */
    private boolean matchesExistingBlock(
            List<String> sourceLines,
            int line,
            List<String> replacementLines
    ) {

        int lastLine =
                line + replacementLines.size() - 1;

        if (lastLine > sourceLines.size()) {
            return false;
        }

        for (int offset = 0; offset < replacementLines.size(); offset++) {

            String existing =
                    normalize(
                            sourceLines.get(
                                    line - 1 + offset
                            )
                    );

            String proposed =
                    normalize(
                            replacementLines.get(offset)
                    );

            if (!existing.equals(proposed)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Normalizes the proposed correction into source lines.
     *
     * Trailing newlines are dropped so that a model response ending with a
     * newline does not introduce an extra blank line.
     */
    private List<String> toLines(String correctedCode) {

        String normalized =
                correctedCode
                        .replace("\r\n", "\n")
                        .replace("\r", "\n");

        while (normalized.endsWith("\n")) {
            normalized = normalized.substring(
                    0,
                    normalized.length() - 1
            );
        }

        if (normalized.isEmpty()) {
            return new ArrayList<>();
        }

        return new ArrayList<>(
                Arrays.asList(
                        normalized.split("\n", -1)
                )
        );
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.strip();
    }

    private PatchResult refused(
            String message,
            int startLine,
            int endLine,
            String sourceCode
    ) {
        return new PatchResult(
                false,
                FixApplicationResult.STATUS_REQUIRES_REVIEW,
                message,
                startLine,
                endLine,
                sourceCode
        );
    }

    /**
     * Result of a scoped patch attempt.
     *
     * {@code sourceCode} is identical to the input whenever the patch was not
     * applied.
     */
    public record PatchResult(
            boolean applied,
            String status,
            String message,
            int startLine,
            int endLine,
            String sourceCode
    ) {

        public boolean requiresReview() {
            return FixApplicationResult.STATUS_REQUIRES_REVIEW
                    .equals(status);
        }
    }
}

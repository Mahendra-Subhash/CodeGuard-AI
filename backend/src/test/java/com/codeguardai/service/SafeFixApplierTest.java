package com.codeguardai.service;

import com.codeguardai.domain.Finding;
import com.codeguardai.domain.FixApplicationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused safety tests for {@link SafeFixApplier}.
 *
 * <p>The applier must never modify a source line it has not validated. These
 * tests pin the contract directly on the applier, independently of ScanService:
 *
 * <ol>
 *   <li>A single-line correction still applies when the anchor matches.</li>
 *   <li>A multi-line correction is refused when only the first line is proven.</li>
 *   <li>A multi-line correction applies only when the complete affected range
 *       is proven by the finding.</li>
 *   <li>A range past the end of the source is refused.</li>
 *   <li>CRLF handling is unchanged.</li>
 *   <li>A refusal leaves the source byte-for-byte unchanged.</li>
 *   <li>Already-applied behaviour is unchanged.</li>
 * </ol>
 */
class SafeFixApplierTest {

    private final SafeFixApplier applier = new SafeFixApplier();

    /*
     * Line 3 is the target. The snippet is proven starting at that line.
     */
    private static final String SOURCE = """
            public class Demo {
                public void run(String value) {
                    int unused = 0;
                }
            }
            """;

    private static final int TARGET_LINE = 3;

    private static final String TARGET_LINE_TEXT =
            "int unused = 0;";

    private static String singleLineReplacement() {
        return "        int unused = 1;";
    }

    /**
     * A finding whose snippet proves exactly {@code snippet} starting at
     * {@code line}.
     */
    private static Finding finding(int line, String snippet) {
        return new Finding(
                "CodeGuard-Test",
                "CG-Test",
                "Quality",
                "MEDIUM",
                "Controlled test finding",
                "Controlled finding used to verify safe fix application.",
                "Demo.java",
                line,
                1,
                snippet,
                "Apply the verified correction."
        );
    }

    private static List<String> lines(String source) {
        return List.of(source.split("\\r?\\n", -1));
    }

    /* 1. A single-line correction keeps working exactly as before. */
    @Test
    void singleLineReplacementSucceedsWhenTheAnchorMatches() {

        SafeFixApplier.PatchResult result =
                applier.apply(
                        SOURCE,
                        finding(TARGET_LINE, TARGET_LINE_TEXT),
                        singleLineReplacement()
                );

        assertThat(result.applied()).isTrue();
        assertThat(result.status())
                .isEqualTo(FixApplicationResult.STATUS_APPLIED);
        assertThat(result.requiresReview()).isFalse();
        assertThat(result.startLine()).isEqualTo(TARGET_LINE);
        assertThat(result.endLine()).isEqualTo(TARGET_LINE);

        assertThat(lines(result.sourceCode())).hasSameSizeAs(lines(SOURCE));

        assertThat(lines(result.sourceCode()).get(TARGET_LINE - 1))
                .isEqualTo(singleLineReplacement());
    }

    /*
     * 2. A multi-line correction must be refused when the finding only proves
     * the single anchor line. This is the defect that allowed unverified lines
     * to be written.
     */
    @Test
    void multiLineReplacementIsRefusedWhenOnlyTheFirstLineIsProven() {

        String multiLine = """
                    int unused = 1;
                statement.setString(1, value);
                """;

        SafeFixApplier.PatchResult result =
                applier.apply(
                        SOURCE,
                        finding(TARGET_LINE, TARGET_LINE_TEXT),
                        multiLine
                );

        assertThat(result.applied()).isFalse();
        assertThat(result.requiresReview()).isTrue();
        assertThat(result.status())
                .isEqualTo(FixApplicationResult.STATUS_REQUIRES_REVIEW);
        assertThat(result.message())
                .contains("could not be validated");

        /*
         * The extra line must not have been injected.
         */
        assertThat(result.sourceCode()).doesNotContain("setString");
        assertThat(result.sourceCode()).isEqualTo(SOURCE);
    }

    /*
     * 3. A multi-line correction succeeds when the finding proves the complete
     * affected range.
     */
    @Test
    void multiLineReplacementSucceedsWhenTheCompleteRangeIsProven() {

        String provenSnippet =
                "int unused = 0;\n    }";

        String multiLine = """
                    int unused = 1;
                }
                """;

        SafeFixApplier.PatchResult result =
                applier.apply(
                        SOURCE,
                        finding(TARGET_LINE, provenSnippet),
                        multiLine
                );

        assertThat(result.applied()).isTrue();
        assertThat(result.status())
                .isEqualTo(FixApplicationResult.STATUS_APPLIED);
        assertThat(result.startLine()).isEqualTo(TARGET_LINE);
        assertThat(result.endLine()).isEqualTo(TARGET_LINE + 1);

        List<String> patched = lines(result.sourceCode());

        /*
         * The applier replaces the single anchor line with the N proven
         * correction lines, so the file grows by N-1. Everything outside the
         * proven range keeps its original content and order.
         */
        assertThat(patched)
                .hasSize(lines(SOURCE).size() - 1 + 2);

        assertThat(patched.subList(0, TARGET_LINE - 1))
                .isEqualTo(lines(SOURCE).subList(0, TARGET_LINE - 1));

        assertThat(patched.get(TARGET_LINE - 1)).isEqualTo("    int unused = 1;");
        assertThat(patched.get(TARGET_LINE)).isEqualTo("}");

        assertThat(patched.subList(TARGET_LINE + 1, patched.size()))
                .isEqualTo(lines(SOURCE).subList(TARGET_LINE, lines(SOURCE).size()));
    }

    /*
     * 3a. The proven range must be contiguous and anchored at the finding line.
     */
    @Test
    void multiLineReplacementPreservesEveryLineOutsideTheProvenRange() {

        String provenSnippet =
                "int unused = 0;\n    }";

        String multiLine =
                "int unused = 1;\n}";

        SafeFixApplier.PatchResult result =
                applier.apply(
                        SOURCE,
                        finding(TARGET_LINE, provenSnippet),
                        multiLine
                );

        assertThat(result.applied()).isTrue();

        List<String> patched = lines(result.sourceCode());

        assertThat(patched.get(0)).isEqualTo("public class Demo {");
        assertThat(patched.get(1)).isEqualTo("    public void run(String value) {");

        /*
         * The closing brace of the method and the class are both preserved
         * after the two proven lines were replaced.
         */
        assertThat(patched).containsSubsequence("    }", "}");
    }

    /*
     * 3b. The complete range must actually match, not merely be the right size.
     */
    @Test
    void multiLineReplacementIsRefusedWhenALaterProvenLineDiffers() {

        String wrongSecondLine = "int unused = 0;\n    totallyDifferent();";

        String multiLine = """
                    int unused = 1;
                }
                """;

        SafeFixApplier.PatchResult result =
                applier.apply(
                        SOURCE,
                        finding(TARGET_LINE, wrongSecondLine),
                        multiLine
                );

        assertThat(result.applied()).isFalse();
        assertThat(result.requiresReview()).isTrue();
        assertThat(result.sourceCode()).isEqualTo(SOURCE);
    }

    /* 4. A range past the end of the source is refused. */
    @Test
    void rangeBeyondEndOfSourceIsRefused() {

        int lastLine = lines(SOURCE).size();

        SafeFixApplier.PatchResult result =
                applier.apply(
                        SOURCE,
                        finding(lastLine, "}"),
                        "}\n    int trailing = 0;\n    int extra = 1;"
                );

        assertThat(result.applied()).isFalse();
        assertThat(result.requiresReview()).isTrue();
        assertThat(result.message())
                .contains("only has");
        assertThat(result.sourceCode()).isEqualTo(SOURCE);
    }

    /* 5. CRLF handling is unchanged for both apply and refuse. */
    @Test
    void crlfIsPreservedOnApply() {

        String crlfSource =
                SOURCE.replace("\n", "\r\n");

        SafeFixApplier.PatchResult result =
                applier.apply(
                        crlfSource,
                        finding(TARGET_LINE, TARGET_LINE_TEXT),
                        singleLineReplacement()
                );

        assertThat(result.applied()).isTrue();

        String patched = result.sourceCode();

        assertThat(patched).contains("\r\n");
        assertThat(patched.replace("\r\n", "")).doesNotContain("\n");
        assertThat(patched).isEqualTo(
                crlfSource.replace(TARGET_LINE_TEXT, "int unused = 1;")
        );
    }

    @Test
    void crlfIsPreservedByteForByteOnRefusal() {

        String crlfSource =
                SOURCE.replace("\n", "\r\n");

        SafeFixApplier.PatchResult result =
                applier.apply(
                        crlfSource,
                        finding(TARGET_LINE, TARGET_LINE_TEXT),
                        "int unused = 1;\nint injected = 2;"
                );

        assertThat(result.applied()).isFalse();
        assertThat(result.sourceCode()).isEqualTo(crlfSource);
        assertThat(result.sourceCode()).contains("\r\n");
    }

    /*
     * 6. Every refusal path returns the original source byte-for-byte.
     */
    @Test
    void everyRefusalLeavesTheSourceByteForByteUnchanged() {

        String multiLine =
                "int unused = 1;\nint injected = 2;";

        SafeFixApplier.PatchResult wrongLine =
                applier.apply(
                        SOURCE,
                        finding(0, TARGET_LINE_TEXT),
                        singleLineReplacement()
                );

        SafeFixApplier.PatchResult outOfRange =
                applier.apply(
                        SOURCE,
                        finding(999, TARGET_LINE_TEXT),
                        singleLineReplacement()
                );

        SafeFixApplier.PatchResult wrongSnippet =
                applier.apply(
                        SOURCE,
                        finding(TARGET_LINE, "int somethingElse = 0;"),
                        singleLineReplacement()
                );

        SafeFixApplier.PatchResult noSnippet =
                applier.apply(
                        SOURCE,
                        finding(TARGET_LINE, "   "),
                        singleLineReplacement()
                );

        SafeFixApplier.PatchResult blankCorrection =
                applier.apply(
                        SOURCE,
                        finding(TARGET_LINE, TARGET_LINE_TEXT),
                        "   "
                );

        SafeFixApplier.PatchResult multiLineCorrection =
                applier.apply(
                        SOURCE,
                        finding(TARGET_LINE, TARGET_LINE_TEXT),
                        multiLine
                );

        assertThat(wrongLine.requiresReview()).isTrue();
        assertThat(outOfRange.requiresReview()).isTrue();
        assertThat(wrongSnippet.requiresReview()).isTrue();
        assertThat(noSnippet.requiresReview()).isTrue();
        assertThat(blankCorrection.requiresReview()).isTrue();
        assertThat(multiLineCorrection.requiresReview()).isTrue();

        for (SafeFixApplier.PatchResult result : List.of(
                wrongLine,
                outOfRange,
                wrongSnippet,
                noSnippet,
                blankCorrection,
                multiLineCorrection
        )) {
            assertThat(result.applied()).isFalse();
            assertThat(result.sourceCode()).isEqualTo(SOURCE);
        }
    }

    /* 7. Already-applied behaviour is unchanged. */
    @Test
    void identicalSingleLineIsReportedAsAlreadyApplied() {

        SafeFixApplier.PatchResult result =
                applier.apply(
                        SOURCE,
                        finding(TARGET_LINE, TARGET_LINE_TEXT),
                        TARGET_LINE_TEXT
                );

        assertThat(result.applied()).isFalse();
        assertThat(result.requiresReview()).isFalse();
        assertThat(result.status())
                .isEqualTo(FixApplicationResult.STATUS_ALREADY_APPLIED);
        assertThat(result.sourceCode()).isEqualTo(SOURCE);
    }

    /*
     * 7b. Already-applied is still reported for a fully proven multi-line
     * block, and it never duplicates code.
     */
    @Test
    void identicalMultiLineBlockIsReportedAsAlreadyApplied() {

        String provenSnippet =
                "int unused = 0;\n    }";

        SafeFixApplier.PatchResult result =
                applier.apply(
                        SOURCE,
                        finding(TARGET_LINE, provenSnippet),
                        "int unused = 0;\n}"
                );

        assertThat(result.applied()).isFalse();
        assertThat(result.requiresReview()).isFalse();
        assertThat(result.status())
                .isEqualTo(FixApplicationResult.STATUS_ALREADY_APPLIED);
        assertThat(result.sourceCode()).isEqualTo(SOURCE);
        assertThat(result.sourceCode().split("\\r?\\n", -1))
                .hasSameSizeAs(SOURCE.split("\\r?\\n", -1));
    }
}

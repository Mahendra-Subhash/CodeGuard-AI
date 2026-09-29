package com.codeguardai.service;

import com.codeguardai.ai.AiAnalysisRequest;
import com.codeguardai.ai.AiAnalysisResponse;
import com.codeguardai.ai.AiInferenceProvider;
import com.codeguardai.config.HeuristicsProperties;
import com.codeguardai.domain.Finding;
import com.codeguardai.domain.FixApplicationResult;
import com.codeguardai.domain.FixProposal;
import com.codeguardai.domain.Scan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScanServiceFixApplicationTest {

    /*
     * Line 6 of SOURCE is the SQL string concatenation line.
     */
    private static final int TARGET_LINE = 6;

    private static final String TARGET_LINE_TEXT =
            "String query = \"SELECT * FROM users WHERE username = '\" + username + \"'\";";

    private static final String SAFE_QUERY_LINE =
            "        String query = \"SELECT * FROM users WHERE username = ?\";";

    private static final int STRUCTURAL_LINE = 5;

    private static final String STRUCTURAL_LINE_TEXT =
            "public void findUser(Connection connection, String username) throws Exception {";

    private static final String EXECUTE_QUERY_LINE_TEXT =
            "statement.executeQuery(query);";

    private static final String SOURCE = """
            import java.sql.Connection;

            public class Demo {

                public void findUser(Connection connection, String username) throws Exception {
                    String query = "SELECT * FROM users WHERE username = '" + username + "'";
                    statement.executeQuery(query);
                }
            }
            """;

    @Test
    void onlyTheFindingLineIsChangedAndUnrelatedLinesSurvive() {

        ScanService service = createService();

        Finding finding =
                testFinding(TARGET_LINE, TARGET_LINE_TEXT);

        Scan scan =
                scanWithFinding(service, finding);

        FixApplicationResult result =
                service.applyFixToScan(
                        scan.getId(),
                        finding.getId(),
                        SAFE_QUERY_LINE
                );

        assertThat(result.applied()).isTrue();
        assertThat(result.status()).isEqualTo(FixApplicationResult.STATUS_APPLIED);
        assertThat(result.startLine()).isEqualTo(TARGET_LINE);
        assertThat(result.endLine()).isEqualTo(TARGET_LINE);

        List<String> originalLines = lines(SOURCE);
        List<String> patchedLines = lines(result.scan().getSourceCode());

        assertThat(patchedLines).hasSameSizeAs(originalLines);

        assertThat(patchedLines.get(TARGET_LINE - 1))
                .isEqualTo(SAFE_QUERY_LINE);

        for (int index = 0; index < originalLines.size(); index++) {

            if (index == TARGET_LINE - 1) {
                continue;
            }

            assertThat(patchedLines.get(index))
                    .as("line %d must be preserved", index + 1)
                    .isEqualTo(originalLines.get(index));
        }

        assertThat(result.scan().getSourceCode())
                .contains("import java.sql.Connection;")
                .contains("public class Demo {")
                .contains("statement.executeQuery(query);")
                .doesNotContain("+ username +");
    }

    /*
     * Finding ids are content derived, so re-scanning the same source
     * produces the same id in a second scan. The lookup must resolve to the
     * newest scan instead of an arbitrary ConcurrentHashMap entry.
     */
    @Test
    void findingResolvesToTheNewestScanThatStillReportsItActive() {

        ScanService service = createService();

        Finding firstOccurrence =
                testFinding(TARGET_LINE, TARGET_LINE_TEXT);

        Scan older =
                scanWithFinding(service, firstOccurrence);

        Finding secondOccurrence =
                testFinding(TARGET_LINE, TARGET_LINE_TEXT);

        Scan newer =
                scanWithFinding(service, secondOccurrence);

        assertThat(secondOccurrence.getId())
                .isEqualTo(firstOccurrence.getId());

        assertThat(newer.getId())
                .isNotEqualTo(older.getId());

        assertThat(service.findScanIdForFinding(firstOccurrence.getId()))
                .isEqualTo(newer.getId());

        FixApplicationResult result =
                service.applyFixToFinding(
                        firstOccurrence.getId(),
                        SAFE_QUERY_LINE
                );

        assertThat(result.applied()).isTrue();
        assertThat(result.scan().getId()).isEqualTo(newer.getId());

        assertThat(newer.getSourceCode())
                .contains("username = ?");

        assertThat(older.getSourceCode())
                .isEqualTo(SOURCE);
    }

    /*
     * An already patched older scan keeps the finding in baselineFindings
     * only. That must not shadow a fresh scan where the same finding is
     * still active, which is what used to answer 409 requires-review.
     */
    @Test
    void anAlreadyPatchedOlderScanDoesNotShadowAFreshScan() {

        ScanService service = createService();

        Finding olderFinding =
                testFinding(TARGET_LINE, TARGET_LINE_TEXT);

        Scan older =
                scanWithFinding(service, olderFinding);

        older.setFindings(new ArrayList<>());
        older.setBaselineFindings(
                new ArrayList<>(List.of(olderFinding))
        );

        Finding freshFinding =
                testFinding(TARGET_LINE, TARGET_LINE_TEXT);

        Scan newer =
                scanWithFinding(service, freshFinding);

        assertThat(freshFinding.getId())
                .isEqualTo(olderFinding.getId());

        FixApplicationResult result =
                service.applyFixToFinding(
                        freshFinding.getId(),
                        SAFE_QUERY_LINE
                );

        assertThat(result.applied()).isTrue();
        assertThat(result.status())
                .isEqualTo(FixApplicationResult.STATUS_APPLIED);
        assertThat(result.scan().getId()).isEqualTo(newer.getId());

        assertThat(newer.getSourceCode())
                .contains("username = ?");

        assertThat(older.getSourceCode())
                .isEqualTo(SOURCE);
    }

    @Test
    void modelOutputIsNeverAppliedToTheWholeFile() {

        ScanService service = createService();

        Finding finding =
                testFinding(TARGET_LINE, TARGET_LINE_TEXT);

        Scan scan =
                scanWithFinding(service, finding);

        /*
         * A multi-line model correction cannot be authorised by a finding that
         * only proves the single anchor line, so the whole-file write that
         * used to happen here is now refused before anything is written.
         */
        String modelOutput = """
                String query = "SELECT * FROM users WHERE username = ?";
                statement.setString(1, username);
                """;

        FixApplicationResult result =
                service.applyFixToScan(
                        scan.getId(),
                        finding.getId(),
                        modelOutput
                );

        assertThat(result.applied()).isFalse();
        assertThat(result.requiresReview()).isTrue();
        assertThat(result.status())
                .isEqualTo(FixApplicationResult.STATUS_REQUIRES_REVIEW);

        assertThat(result.scan().getSourceCode())
                .isEqualTo(SOURCE);

        assertThat(result.scan().getSourceCode())
                .doesNotContain("setString");
    }

    @Test
    void invalidOrMissingRangeLeavesTheSourceUntouched() {

        ScanService service = createService();

        Scan scan =
                service.createScan(
                        SOURCE,
                        "Demo.java"
                );

        assertRangeRefused(service, scan, testFinding(0, TARGET_LINE_TEXT));
        assertRangeRefused(service, scan, testFinding(-4, TARGET_LINE_TEXT));
        assertRangeRefused(service, scan, testFinding(999, TARGET_LINE_TEXT));
        assertRangeRefused(service, scan, testFinding(TARGET_LINE, ""));
        assertRangeRefused(service, scan, testFinding(TARGET_LINE, "   "));
        assertRangeRefused(service, scan, testFinding(TARGET_LINE, "int unrelated = 1;"));
    }

    @Test
    void applyingTheSameCorrectionTwiceDoesNotDuplicateCode() {

        ScanService service = createService();

        /*
         * The finding proves two consecutive lines, so a two-line correction
         * is a fully validated range and may be applied.
         */
        String provenSnippet =
                TARGET_LINE_TEXT
                        + "\n"
                        + EXECUTE_QUERY_LINE_TEXT;

        Finding finding =
                testFinding(TARGET_LINE, provenSnippet);

        Scan scan =
                scanWithFinding(service, finding);

        String replacement = """
                String query = "SELECT * FROM users WHERE username = ?";
                statement.setString(1, username);
                """;

        FixApplicationResult first =
                service.applyFixToScan(
                        scan.getId(),
                        finding.getId(),
                        replacement
                );

        assertThat(first.applied()).isTrue();
        assertThat(first.status()).isEqualTo(FixApplicationResult.STATUS_APPLIED);

        String afterFirstApply = first.scan().getSourceCode();

        assertThat(lines(afterFirstApply))
                .hasSize(lines(SOURCE).size() + 1);

        assertThat(countOccurrences(afterFirstApply, "statement.setString(1, username);"))
                .isEqualTo(1);

        FixApplicationResult second =
                service.applyFixToScan(
                        scan.getId(),
                        finding.getId(),
                        replacement
                );

        assertThat(second.applied()).isFalse();
        assertThat(second.status()).isEqualTo(FixApplicationResult.STATUS_REQUIRES_REVIEW);
        assertThat(second.message()).isNotBlank();
        assertThat(second.scan().getSourceCode()).isEqualTo(afterFirstApply);
        assertThat(lines(second.scan().getSourceCode()))
                .hasSameSizeAs(lines(afterFirstApply));
        assertThat(countOccurrences(second.scan().getSourceCode(), "statement.setString(1, username);"))
                .isEqualTo(1);
    }

    @Test
    void applyingAnIdenticalLineTwiceWritesNothing() {

        ScanService service = createService();

        Finding finding =
                testFinding(TARGET_LINE, TARGET_LINE_TEXT);

        Scan scan =
                scanWithFinding(service, finding);

        String identicalLine =
                "        " + TARGET_LINE_TEXT;

        FixApplicationResult first =
                service.applyFixToScan(
                        scan.getId(),
                        finding.getId(),
                        identicalLine
                );

        assertThat(first.applied()).isFalse();
        assertThat(first.status()).isEqualTo(FixApplicationResult.STATUS_ALREADY_APPLIED);
        assertThat(first.requiresReview()).isFalse();
        assertThat(first.scan().getSourceCode()).isEqualTo(SOURCE);

        FixApplicationResult second =
                service.applyFixToScan(
                        scan.getId(),
                        finding.getId(),
                        identicalLine
                );

        assertThat(second.applied()).isFalse();
        assertThat(second.status()).isEqualTo(FixApplicationResult.STATUS_ALREADY_APPLIED);
        assertThat(second.scan().getSourceCode()).isEqualTo(SOURCE);
        assertThat(lines(second.scan().getSourceCode()))
                .hasSameSizeAs(lines(SOURCE));
        assertThat(countOccurrences(second.scan().getSourceCode(), TARGET_LINE_TEXT))
                .isEqualTo(1);
    }

    @Test
    void unrelatedLinesArePreservedWithWindowsLineEndings() {

        ScanService service = createService();

        String crlfSource =
                SOURCE.replace("\n", "\r\n");

        Finding finding =
                testFinding(TARGET_LINE, TARGET_LINE_TEXT);

        Scan scan =
                service.createScan(
                        crlfSource,
                        "Demo.java"
                );

        scan.setFindings(
                new ArrayList<>(
                        List.of(finding)
                )
        );

        FixApplicationResult result =
                service.applyFixToScan(
                        scan.getId(),
                        finding.getId(),
                        SAFE_QUERY_LINE
                );

        assertThat(result.applied()).isTrue();

        String patched = result.scan().getSourceCode();

        assertThat(patched).contains("\r\n");

        assertThat(patched.replace("\r\n", "")).doesNotContain("\n");

        List<String> originalLines = lines(crlfSource);
        List<String> patchedLines = lines(patched);

        assertThat(patchedLines).hasSameSizeAs(originalLines);

        assertThat(patchedLines.get(TARGET_LINE - 1))
                .isEqualTo(SAFE_QUERY_LINE);

        for (int index = 0; index < originalLines.size(); index++) {

            if (index == TARGET_LINE - 1) {
                continue;
            }

            assertThat(patchedLines.get(index))
                    .as("line %d must be preserved", index + 1)
                    .isEqualTo(originalLines.get(index));
        }
    }

    @Test
    void realAnalyzerMetadataScopesTheFixToTheReportedLine() {

        ScanService service = createServiceWithHeuristics();

        Scan scan =
                service.createScan(
                        SOURCE,
                        "Demo.java"
                );

        Finding finding =
                scan.getFindings()
                        .stream()
                        .filter(candidate -> candidate.getLine() == TARGET_LINE + 1)
                        .filter(candidate -> EXECUTE_QUERY_LINE_TEXT.equals(candidate.getCodeSnippet()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "The analyzers must report the executeQuery finding."));

        String replacement =
                "        // corrected by CodeGuard test";

        FixApplicationResult result =
                service.applyFixToScan(
                        scan.getId(),
                        finding.getId(),
                        replacement
                );

        assertThat(result.applied()).isTrue();
        assertThat(result.startLine()).isEqualTo(finding.getLine());
        assertThat(result.endLine()).isEqualTo(finding.getLine());

        List<String> originalLines = lines(SOURCE);
        List<String> patchedLines = lines(result.scan().getSourceCode());

        assertThat(patchedLines).hasSameSizeAs(originalLines);

        assertThat(patchedLines.get(finding.getLine() - 1))
                .isEqualTo(replacement);

        for (int index = 0; index < originalLines.size(); index++) {

            if (index == finding.getLine() - 1) {
                continue;
            }

            assertThat(patchedLines.get(index))
                    .as("line %d must be preserved", index + 1)
                    .isEqualTo(originalLines.get(index));
        }
    }

    @Test
    void unusableCorrectionIsRevertedAndRequiresReview() {

        ScanService service = createService();

        Finding finding =
                testFinding(STRUCTURAL_LINE, STRUCTURAL_LINE_TEXT);

        Scan scan =
                scanWithFinding(service, finding);

        FixApplicationResult result =
                service.applyFixToScan(
                        scan.getId(),
                        finding.getId(),
                        "        // broken by the model"
                );

        assertThat(result.applied()).isFalse();
        assertThat(result.requiresReview()).isTrue();
        assertThat(result.status()).isEqualTo(FixApplicationResult.STATUS_REQUIRES_REVIEW);
        assertThat(result.message()).isNotBlank();

        assertThat(result.scan().getSourceCode())
                .as("an unusable correction must not be kept")
                .isEqualTo(SOURCE);

        assertThat(scan.getSourceCode()).isEqualTo(SOURCE);
        assertThat(scan.getFindings()).containsExactly(finding);
    }

    private static void assertRangeRefused(
            ScanService service,
            Scan scan,
            Finding finding
    ) {

        scan.setFindings(
                new ArrayList<>(
                        List.of(finding)
                )
        );

        FixApplicationResult result =
                service.applyFixToScan(
                        scan.getId(),
                        finding.getId(),
                        SAFE_QUERY_LINE
                );

        assertThat(result.applied()).isFalse();
        assertThat(result.requiresReview()).isTrue();
        assertThat(result.status()).isEqualTo(FixApplicationResult.STATUS_REQUIRES_REVIEW);
        assertThat(result.message()).isNotBlank();

        assertThat(result.scan().getSourceCode())
                .as("the source returned to the caller must not be modified")
                .isEqualTo(SOURCE);

        assertThat(scan.getSourceCode()).isEqualTo(SOURCE);
        assertThat(scan.getFindings()).containsExactly(finding);
    }

    private static ScanService createService() {
        return new ScanService(
                new StaticAnalysisService(
                        new PmdAnalyzer(),
                        new CheckstyleAnalyzer(),
                        new SpotBugsAnalyzer()
                ),
                new StubAiInferenceProvider(),
                new SafeFixApplier()
        );
    }

    /*
     * The CodeGuard heuristic layer is disabled by default. This test needs a
     * finding anchored on the executeQuery line, so it opts in explicitly.
     */
    private static ScanService createServiceWithHeuristics() {

        HeuristicsProperties heuristicsProperties =
                new HeuristicsProperties();

        heuristicsProperties.setEnabled(true);

        return new ScanService(
                new StaticAnalysisService(
                        new PmdAnalyzer(),
                        new CheckstyleAnalyzer(),
                        new SpotBugsAnalyzer(),
                        heuristicsProperties
                ),
                new StubAiInferenceProvider(),
                new SafeFixApplier()
        );
    }

    private static Scan scanWithFinding(
            ScanService service,
            Finding finding
    ) {

        Scan scan =
                service.createScan(
                        SOURCE,
                        "Demo.java"
                );

        scan.setFindings(
                new ArrayList<>(
                        List.of(finding)
                )
        );

        return scan;
    }

    private static Finding testFinding(
            int line,
            String snippet
    ) {
        return new Finding(
                "CodeGuard-Test",
                "CG-UnsafeQuery",
                "Security",
                "HIGH",
                "Controlled test finding",
                "Controlled finding used to verify safe fix application.",
                "Demo.java",
                line,
                1,
                snippet,
                "Bind parameters instead of concatenating user input."
        );
    }

    /*
     * Logical lines of a source string, ignoring a single trailing newline.
     */
    private static List<String> lines(String source) {

        List<String> raw =
                new ArrayList<>(
                        Arrays.asList(
                                source.split("\r?\n", -1)
                        )
                );

        if (!raw.isEmpty() &&
                raw.get(raw.size() - 1).isEmpty()) {
            raw.remove(raw.size() - 1);
        }

        return raw;
    }

    private static int countOccurrences(
            String text,
            String token
    ) {

        int count = 0;
        int index = 0;

        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }

        return count;
    }

    private static class StubAiInferenceProvider
            implements AiInferenceProvider {

        @Override
        public String providerName() {
            return "stub";
        }

        @Override
        public AiAnalysisResponse analyze(AiAnalysisRequest request) {
            return new AiAnalysisResponse(
                    "summary",
                    "explanation",
                    "rootCause",
                    "risk",
                    "recommendedFix",
                    "correctedCode",
                    "uncalibrated",
                    "verificationHint"
            );
        }

        @Override
        public FixProposal proposeFix(AiAnalysisRequest request) {
            return new FixProposal(
                    "originalCode",
                    "proposedCode",
                    "diff",
                    "rationale"
            );
        }
    }
}


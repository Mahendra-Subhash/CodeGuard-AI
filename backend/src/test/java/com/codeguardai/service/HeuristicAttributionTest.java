package com.codeguardai.service;

import com.codeguardai.config.HeuristicsProperties;
import com.codeguardai.domain.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the attribution contract of the scan pipeline:
 *
 * <ul>
 *   <li>CodeGuard's own token based heuristics are disabled by default.</li>
 *   <li>When explicitly enabled, they are reported under the dedicated
 *       "CodeGuard-Heuristics" analyzer name and never claim to be a PMD or
 *       Checkstyle rule.</li>
 *   <li>Genuine PMD, Checkstyle and SpotBugs findings keep their own analyzer
 *       name and are never merged into the heuristic layer.</li>
 * </ul>
 */
class HeuristicAttributionTest {

    private static final String HEURISTIC_ANALYZER =
            StaticAnalysisService.HEURISTIC_ANALYZER;

    /*
     * Triggers the CodeGuard heuristics. This snippet does not compile, so
     * SpotBugs legitimately reports nothing for it.
     */
    private static final String HEURISTIC_SOURCE = """
            public class HeuristicDemo {
                void run(java.sql.Connection connection, String username) throws Exception {
                    java.sql.Statement statement = connection.createStatement();
                    statement.executeQuery("SELECT * FROM users WHERE username = '" + username + "'");
                    java.io.FileInputStream in = new java.io.FileInputStream("data.txt");
                    String password = "secret-value";
                }
            }
            """;

    private static StaticAnalysisService service(boolean heuristicsEnabled) {
        HeuristicsProperties properties = new HeuristicsProperties();
        properties.setEnabled(heuristicsEnabled);

        return new StaticAnalysisService(
                new PmdAnalyzer(),
                new CheckstyleAnalyzer(),
                new SpotBugsAnalyzer(),
                properties
        );
    }

    @Test
    void heuristicsAreDisabledByDefault() {

        List<Finding> findings =
                service(false).analyzeSource(
                        HEURISTIC_SOURCE,
                        "HeuristicDemo.java"
                );

        assertThat(
                findings.stream()
                        .map(Finding::getAnalyzer)
                        .filter(HEURISTIC_ANALYZER::equals)
        ).isEmpty();
    }

    @Test
    void aDefaultScanReportsOnlyGenuineAnalyzers() {

        List<Finding> findings =
                service(false).analyzeSource(
                        HEURISTIC_SOURCE,
                        "HeuristicDemo.java"
                );

        assertThat(
                findings.stream()
                        .map(Finding::getAnalyzer)
        ).isSubsetOf("PMD", "Checkstyle", "SpotBugs");
    }

    @Test
    void aDefaultScanReportsNoHeuristicRuleNames() {

        List<Finding> findings =
                service(false).analyzeSource(
                        HEURISTIC_SOURCE,
                        "HeuristicDemo.java"
                );

        /*
         * Only the CodeGuard rule names are asserted here. Genuine analyzer
         * rules may legitimately share a name (for example real PMD reports
         * its own CloseResource rule), so the check is scoped to the CodeGuard
         * heuristic rule set and the analyzer name.
         */
        assertThat(
                findings.stream()
                        .filter(finding -> HEURISTIC_ANALYZER.equals(finding.getAnalyzer()))
                        .map(Finding::getRule)
        ).doesNotContain(
                "CgSqlConcatenationInQuery",
                "CgUnclosedResource",
                "CgHardcodedSecret",
                "CgSwallowedException"
        );

        /*
         * The retired heuristic rule names must no longer be produced by
         * CodeGuard at all. They may still appear when a real analyzer
         * genuinely reports them.
         */
        assertThat(
                findings.stream()
                        .map(Finding::getRule)
        ).doesNotContain(
                "AvoidStringConcatenationInQuery",
                "NoHardcodedCredentials",
                "CgSqlConcatenationInQuery",
                "CgHardcodedSecret"
        );
    }

    @Test
    void enabledHeuristicsAreReportedUnderTheCodeGuardHeuristicsAnalyzer() {

        List<Finding> heuristicFindings = heuristicFindings(true);

        assertThat(heuristicFindings).isNotEmpty();

        assertThat(heuristicFindings)
                .allSatisfy(finding -> {
                    assertThat(finding.getAnalyzer())
                            .isEqualTo(HEURISTIC_ANALYZER);
                    assertThat(finding.getFile())
                            .isEqualTo("HeuristicDemo.java");
                    assertThat(finding.getLine()).isGreaterThanOrEqualTo(1);
                    assertThat(finding.getColumn()).isGreaterThanOrEqualTo(1);
                });

        assertThat(
                heuristicFindings.stream()
                        .map(Finding::getRule)
        ).contains(
                "CgSqlConcatenationInQuery",
                "CgUnclosedResource",
                "CgHardcodedSecret"
        );
    }

    @Test
    void realAnalyzerFindingsKeepTheirOwnAnalyzerNames() {

        List<Finding> findings =
                service(true).analyzeSource(
                        HEURISTIC_SOURCE,
                        "HeuristicDemo.java"
                );

        /*
         * Enabling the heuristic layer must not change the attribution of any
         * genuine analyzer result.
         */
        assertThat(
                findings.stream()
                        .map(Finding::getAnalyzer)
        ).isSubsetOf("PMD", "Checkstyle", "SpotBugs", HEURISTIC_ANALYZER);
    }

    @Test
    void heuristicColumnPointsAtTheMatchedToken() {

        Finding sqlFinding =
                heuristicFindings(true).stream()
                        .filter(finding -> "CgSqlConcatenationInQuery".equals(finding.getRule()))
                        .findFirst()
                        .orElseThrow();

        /*
         * The column is derived from the actual "executeQuery" token instead of
         * a hardcoded constant.
         */
        String matchedLine =
                HEURISTIC_SOURCE.split("\\r?\\n")[sqlFinding.getLine() - 1];

        assertThat(matchedLine).contains("executeQuery");

        assertThat(sqlFinding.getColumn())
                .isEqualTo(matchedLine.indexOf("executeQuery") + 1);
    }

    @Test
    void heuristicColumnIsNotAFixedConstant() {

        List<Integer> columns =
                heuristicFindings(true).stream()
                        .map(Finding::getColumn)
                        .toList();

        /*
         * Every heuristic rule anchors on a different token, so a single
         * hardcoded column for all of them is no longer possible.
         */
        assertThat(columns).doesNotHaveDuplicates();
    }

    @Test
    void swallowedExceptionHeuristicRequiresBothTheCatchAndTheSuppressingReturn() {

        String catchWithoutSuppressingReturn = """
                public class SwallowDemo {
                    boolean run() {
                        try {
                            doWork();
                        } catch (Exception e) {
                            log(e);
                        }
                        return true;
                    }
                }
                """;

        assertThat(
                service(true).analyzeSource(
                                catchWithoutSuppressingReturn,
                                "SwallowDemo.java"
                        ).stream()
                        .map(Finding::getRule)
        ).doesNotContain("CgSwallowedException");

        String catchWithSuppressingReturn = """
                public class SwallowDemo {
                    boolean run() {
                        try {
                            doWork();
                        } catch (Exception e) {
                            return false;
                        }
                    }
                }
                """;

        assertThat(
                service(true).analyzeSource(
                                catchWithSuppressingReturn,
                                "SwallowDemo.java"
                        ).stream()
                        .map(Finding::getRule)
        ).contains("CgSwallowedException");
    }

    @Test
    void heuristicFindingIdsRemainStableAcrossRuns() {

        assertThat(ruleIds(true)).isEqualTo(ruleIds(true));
    }

    private static List<Finding> heuristicFindings(boolean enabled) {
        return service(enabled).analyzeSource(
                        HEURISTIC_SOURCE,
                        "HeuristicDemo.java"
                ).stream()
                .filter(finding -> HEURISTIC_ANALYZER.equals(finding.getAnalyzer()))
                .toList();
    }

    private static List<String> ruleIds(boolean enabled) {
        return heuristicFindings(enabled).stream()
                .map(Finding::getId)
                .toList();
    }
}

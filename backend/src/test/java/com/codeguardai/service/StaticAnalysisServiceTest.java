package com.codeguardai.service;

import com.codeguardai.domain.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that PMD, Checkstyle and SpotBugs all run inside the production scan
 * pipeline and that their findings coexist in a single scan result.
 */
class StaticAnalysisServiceTest {

    private final StaticAnalysisService service =
            new StaticAnalysisService(
                    new PmdAnalyzer(),
                    new CheckstyleAnalyzer(),
                    new SpotBugsAnalyzer()
            );

    /*
     * Triggers all three analyzers at once:
     *
     * - PMD NoPackage (no package declaration)
     * - Checkstyle LineLength (line longer than 120 characters)
     * - SpotBugs NP_ALWAYS_NULL (definite null dereference)
     */
    private static final String MULTI_ANALYZER_SOURCE = """
            public class AllAnalyzersDemo {

                public String inspect(String value) {
                    String unusedLocal = "never read";
                    String text = null;
                    // This deliberately long line exists only to exceed the Checkstyle LineLength limit of one hundred and twenty characters.
                    return text.trim();
                }
            }
            """;

    private static final String FILE_NAME =
            "AllAnalyzersDemo.java";

    private static final String SQL_SOURCE = """
            import java.sql.*;

            public class SqlInjectionDemo {
                public ResultSet loadUser(String username, Connection connection) throws SQLException {
                    Statement statement = connection.createStatement();
                    return statement.executeQuery("SELECT * FROM users WHERE username = '" + username + "'");
                }
            }
            """;

    /*
     * Triggers both removed heuristics (null risk and equals/hashCode) so the
     * real SpotBugs rules can be asserted instead.
     */
    private static final String LEGACY_HEURISTIC_SOURCE = """
            public class LegacyHeuristicDemo {

                public String read(String value) {
                    String text = null;
                    if (value != null) {
                        text = value;
                    }
                    return text.trim();
                }

                public boolean equals(Object candidate) {
                    return candidate != null;
                }
            }
            """;

    @Test
    void pmdFindingsAreIncludedInTheScan() {

        List<Finding> findings = scan(MULTI_ANALYZER_SOURCE, FILE_NAME);

        Finding pmd = first(findings, "PMD", "NoPackage");

        assertThat(pmd.getSeverity()).isEqualTo("MEDIUM");
        assertThat(pmd.getMessage()).isNotBlank();
        assertThat(pmd.getFile()).isEqualTo(FILE_NAME);
        assertThat(pmd.getLine()).isEqualTo(1);
        assertThat(pmd.getColumn()).isGreaterThan(0);
        assertThat(pmd.getCodeSnippet()).contains("public class AllAnalyzersDemo");
        assertThat(pmd.getId()).isNotBlank();
    }

    @Test
    void checkstyleFindingsAreIncludedInTheScan() {

        List<Finding> findings = scan(MULTI_ANALYZER_SOURCE, FILE_NAME);

        Finding checkstyle = findings.stream()
                .filter(finding -> "Checkstyle".equals(finding.getAnalyzer()))
                .filter(finding -> finding.getRule().contains("LineLength"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Checkstyle LineLength finding missing: " + describe(findings)
                ));

        assertThat(checkstyle.getSeverity()).isEqualTo("MEDIUM");
        assertThat(checkstyle.getMessage()).isNotBlank();
        assertThat(checkstyle.getFile()).isEqualTo(FILE_NAME);
        assertThat(checkstyle.getLine()).isEqualTo(6);
        assertThat(checkstyle.getCodeSnippet()).contains("deliberately long line");
        assertThat(checkstyle.getId()).isNotBlank();
    }

    @Test
    void spotBugsFindingsAreIncludedInTheScan() {

        List<Finding> findings = scan(MULTI_ANALYZER_SOURCE, FILE_NAME);

        Finding spotBugs = first(findings, "SpotBugs", "NP_ALWAYS_NULL");

        assertThat(spotBugs.getSeverity()).isEqualTo("HIGH");
        assertThat(spotBugs.getCategory()).isEqualTo("CORRECTNESS");
        assertThat(spotBugs.getTitle()).isNotBlank();
        assertThat(spotBugs.getMessage()).contains("Null pointer dereference");
        assertThat(spotBugs.getFile()).isEqualTo(FILE_NAME);
        assertThat(spotBugs.getLine()).isEqualTo(7);
        assertThat(spotBugs.getCodeSnippet()).isEqualTo("return text.trim();");
        assertThat(spotBugs.getId()).isNotBlank();
    }

    @Test
    void realSpotBugsSecurityRuleIsIncludedForSqlStringConcatenation() {

        List<Finding> findings = scan(SQL_SOURCE, "SqlInjectionDemo.java");

        Finding spotBugs = first(
                findings,
                "SpotBugs",
                "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"
        );

        assertThat(spotBugs.getSeverity()).isEqualTo("HIGH");
        assertThat(spotBugs.getCategory()).isEqualTo("SECURITY");
        assertThat(spotBugs.getLine()).isEqualTo(6);
        assertThat(spotBugs.getCodeSnippet()).contains("executeQuery");
    }

    @Test
    void allThreeAnalyzersCoexistInOneScan() {

        List<Finding> findings = scan(MULTI_ANALYZER_SOURCE, FILE_NAME);

        assertThat(analyzers(findings))
                .containsExactlyInAnyOrder("PMD", "Checkstyle", "SpotBugs");

        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.getId()).isNotBlank();
            assertThat(finding.getAnalyzer()).isNotBlank();
            assertThat(finding.getRule()).isNotBlank();
            assertThat(finding.getCategory()).isNotBlank();
            assertThat(finding.getSeverity()).isNotBlank();
            assertThat(finding.getTitle()).isNotBlank();
            assertThat(finding.getMessage()).isNotBlank();
            assertThat(finding.getFile()).isEqualTo(FILE_NAME);
            assertThat(finding.getLine()).isGreaterThanOrEqualTo(1);
            assertThat(finding.getColumn()).isGreaterThanOrEqualTo(1);
            assertThat(finding.getSuggestedContext()).isNotBlank();
        });
    }

    @Test
    void fabricatedSpotBugsFindingsAreNoLongerProduced() {

        List<Finding> findings = scan(
                LEGACY_HEURISTIC_SOURCE,
                "LegacyHeuristicDemo.java"
        );

        // the removed .contains() based SpotBugs heuristics must never appear
        assertThat(findings)
                .noneMatch(finding ->
                        "SpotBugs".equals(finding.getAnalyzer())
                                && ("NullPointerDereference".equals(finding.getRule())
                                || "EqualsHashCodeContract".equals(finding.getRule()))
                );

        // while the real SpotBugs analyzer reports the genuine rules
        assertThat(rulesOf(findings, "SpotBugs"))
                .contains("NP_NULL_ON_SOME_PATH")
                .contains("HE_EQUALS_USE_HASHCODE");
    }

    @Test
    void nonCompilableSourceStillReturnsTheOtherAnalyzers() {

        String brokenSource = """
                public class BrokenDemo {
                    public void run() {
                        missingMethod("value");
                    }
                }
                """;

        List<Finding> findings = scan(brokenSource, "BrokenDemo.java");

        /*
         * SpotBugs analyses bytecode, so it is skipped for a snippet that does
         * not compile; PMD and Checkstyle results are still returned.
         */
        assertThat(findings).isNotEmpty();
        assertThat(analyzers(findings)).contains("PMD");
        assertThat(analyzers(findings)).doesNotContain("SpotBugs");
    }

    private List<Finding> scan(String source, String fileName) {
        return service.analyzeSource(source, fileName);
    }

    private static Finding first(
            List<Finding> findings,
            String analyzer,
            String rule) {

        return findings.stream()
                .filter(finding -> analyzer.equals(finding.getAnalyzer()))
                .filter(finding -> rule.equals(finding.getRule()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        analyzer + " " + rule + " finding missing: " + describe(findings)
                ));
    }

    private static Set<String> analyzers(List<Finding> findings) {

        return findings.stream()
                .map(Finding::getAnalyzer)
                .collect(Collectors.toSet());
    }

    private static Set<String> rulesOf(
            List<Finding> findings,
            String analyzer) {

        return findings.stream()
                .filter(finding -> analyzer.equals(finding.getAnalyzer()))
                .map(Finding::getRule)
                .collect(Collectors.toSet());
    }

    private static String describe(List<Finding> findings) {

        return findings.stream()
                .map(finding -> finding.getAnalyzer()
                        + ":" + finding.getRule()
                        + "@" + finding.getLine())
                .collect(Collectors.joining(", "));
    }
}


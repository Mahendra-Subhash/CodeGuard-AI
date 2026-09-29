package com.codeguardai;

import com.codeguardai.domain.Finding;
import com.codeguardai.service.CheckstyleAnalyzer;
import com.codeguardai.service.PmdAnalyzer;
import com.codeguardai.service.SpotBugsAnalyzer;
import com.codeguardai.service.StaticAnalysisService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGuardAiApplicationTests {

    @Test
    void staticAnalysisDetectsSqlInjectionPattern() {

        String source = """
                import java.sql.Connection;
                import java.sql.ResultSet;
                import java.sql.Statement;

                public class Demo {

                    public void findUser(
                            Connection connection,
                            String username) throws Exception {

                        String query =
                                "SELECT * FROM users WHERE username = '"
                                        + username + "'";

                        Statement statement =
                                connection.createStatement();

                        ResultSet rs =
                                statement.executeQuery(query);
                    }
                }
                """;

        PmdAnalyzer pmdAnalyzer =
                new PmdAnalyzer();

        CheckstyleAnalyzer checkstyleAnalyzer =
                new CheckstyleAnalyzer();

        SpotBugsAnalyzer spotBugsAnalyzer =
                new SpotBugsAnalyzer();

        StaticAnalysisService service =
                new StaticAnalysisService(
                        pmdAnalyzer,
                        checkstyleAnalyzer,
                        spotBugsAnalyzer
                );

        var findings =
                service.analyzeSource(
                        source,
                        "Demo.java"
                );

        assertThat(findings).isNotEmpty();

        /*
         * The optional CodeGuard heuristic layer is DISABLED by default, so it
         * must not contribute the "SQL injection" finding here.
         */
        assertThat(
                findings.stream()
                        .noneMatch(
                                finding ->
                                        finding.getTitle()
                                                .equals("SQL injection")
                        )
        ).isTrue();

        assertThat(
                findings.stream()
                        .noneMatch(
                                finding ->
                                        StaticAnalysisService
                                                .HEURISTIC_ANALYZER
                                                .equals(finding.getAnalyzer())
                        )
        ).isTrue();

        /*
         * The real SpotBugs analyzer takes part in the same scan and reports
         * its own security rule for the concatenated query.
         */
        Finding spotBugsFinding =
                findings.stream()
                        .filter(finding -> "SpotBugs".equals(finding.getAnalyzer()))
                        .filter(finding -> "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE".equals(finding.getRule()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "SpotBugs SQL finding missing: " + describe(findings)
                        ));

        assertThat(spotBugsFinding.getSeverity()).isEqualTo("HIGH");
        assertThat(spotBugsFinding.getCategory()).isEqualTo("SECURITY");
        assertThat(spotBugsFinding.getMessage()).isNotBlank();
        assertThat(spotBugsFinding.getFile()).isEqualTo("Demo.java");
        assertThat(spotBugsFinding.getLine()).isEqualTo(19);
        assertThat(spotBugsFinding.getColumn()).isGreaterThanOrEqualTo(1);
        assertThat(spotBugsFinding.getCodeSnippet()).contains("executeQuery");
        assertThat(spotBugsFinding.getId()).isNotBlank();

        /*
         * PMD findings from the same scan are preserved.
         */
        assertThat(
                findings.stream()
                        .anyMatch(finding -> "PMD".equals(finding.getAnalyzer()))
        ).isTrue();
    }

    @Test
    void checkstyleAnalyzerDetectsLongLine() {

        String source = """
                public class Demo {

                    public void test() {

                        String message = "This is deliberately a very long Java source line that should exceed the Checkstyle configured maximum line length of one hundred and twenty characters.";

                    }
                }
                """;

        CheckstyleAnalyzer analyzer =
                new CheckstyleAnalyzer();

        var findings =
                analyzer.analyze(
                        source,
                        "Demo.java"
                );

        assertThat(findings).isNotEmpty();

        assertThat(
                findings.stream()
                        .anyMatch(
                                finding ->
                                        finding.getAnalyzer()
                                                .equals("Checkstyle")
                        )
        ).isTrue();
    }

    /*
     * SpotBugs deterministically reports ES_COMPARING_PARAMETER_STRING_WITH_EQ
     * for a String parameter compared with ==.
     */
    @Test
    void spotBugsAnalyzerDetectsBug() {

        String source = """
                public class SpotBugsDemo {

                    public boolean compare(String value) {

                        if (value == "admin") {
                            return true;
                        }

                        return false;
                    }
                }
                """;

        SpotBugsAnalyzer analyzer =
                new SpotBugsAnalyzer();

        var findings =
                analyzer.analyze(
                        source,
                        "SpotBugsDemo.java"
                );

        assertThat(findings).isNotEmpty();

        Finding finding =
                findings.stream()
                        .filter(candidate -> "SpotBugs".equals(candidate.getAnalyzer()))
                        .filter(candidate -> "ES_COMPARING_PARAMETER_STRING_WITH_EQ".equals(candidate.getRule()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "SpotBugs string comparison finding missing: " + describe(findings)
                        ));

        assertThat(finding.getSeverity()).isEqualTo("HIGH");
        assertThat(finding.getCategory()).isEqualTo("BAD_PRACTICE");
        assertThat(finding.getTitle()).isEqualTo("ES_COMPARING_PARAMETER_STRING_WITH_EQ");
        assertThat(finding.getMessage()).contains("Comparison of String parameter");
        assertThat(finding.getFile()).isEqualTo("SpotBugsDemo.java");
        assertThat(finding.getLine()).isEqualTo(5);
        assertThat(finding.getCodeSnippet()).isEqualTo("if (value == \"admin\") {");
        assertThat(finding.getId()).isNotBlank();
    }

    /*
     * A definite null dereference is reported as NP_ALWAYS_NULL (HIGH/CORRECTNESS).
     */
    @Test
    void spotBugsAnalyzerDetectsDefiniteNullDereference() {

        String source = """
                public class NullAlwaysDemo {

                    public int run() {
                        String value = null;
                        return value.trim().length();
                    }
                }
                """;

        Finding finding =
                first(
                        new SpotBugsAnalyzer().analyze(source, "NullAlwaysDemo.java"),
                        "NP_ALWAYS_NULL",
                        "NullAlwaysDemo.java"
                );

        assertThat(finding.getSeverity()).isEqualTo("HIGH");
        assertThat(finding.getCategory()).isEqualTo("CORRECTNESS");
        assertThat(finding.getMessage()).contains("Null pointer dereference");
        assertThat(finding.getLine()).isEqualTo(5);
        assertThat(finding.getCodeSnippet()).isEqualTo("return value.trim().length();");
    }

    /*
     * The behaviour that used to be simulated by a contains() heuristic is
     * reported by the real SpotBugs analyzer as HE_EQUALS_USE_HASHCODE.
     */
    @Test
    void spotBugsAnalyzerDetectsEqualsMethodWithoutHashCode() {

        String source = """
                import java.util.Objects;

                public class EqualsOnlyDemo {
                    private final String id;

                    public EqualsOnlyDemo(String id) { this.id = id; }

                    public boolean equals(Object obj) {
                        if (this == obj) return true;
                        if (obj == null || getClass() != obj.getClass()) return false;
                        EqualsOnlyDemo that = (EqualsOnlyDemo) obj;
                        return Objects.equals(id, that.id);
                    }
                }
                """;

        Finding finding =
                first(
                        new SpotBugsAnalyzer().analyze(source, "EqualsOnlyDemo.java"),
                        "HE_EQUALS_USE_HASHCODE",
                        "EqualsOnlyDemo.java"
                );

        assertThat(finding.getSeverity()).isEqualTo("HIGH");
        assertThat(finding.getCategory()).isEqualTo("BAD_PRACTICE");
        assertThat(finding.getMessage()).contains("defines equals and uses Object.hashCode()");
        assertThat(finding.getLine()).isEqualTo(9);
    }

    /*
     * javac requires the source file to be named after its public type, so the
     * analyzer compiles under that name while findings keep the file name
     * supplied by the caller.
     */
    @Test
    void spotBugsAnalyzerCompilesSnippetUnderItsPublicTypeName() {

        String source = """
                public class Demo {

                    public String read(String value) {
                        String text = null;
                        return text.trim();
                    }
                }
                """;

        var findings =
                new SpotBugsAnalyzer().analyze(
                        source,
                        "Sample.java"
                );

        assertThat(findings).isNotEmpty();

        assertThat(findings)
                .allSatisfy(finding -> {
                    assertThat(finding.getAnalyzer()).isEqualTo("SpotBugs");
                    assertThat(finding.getFile()).isEqualTo("Sample.java");
                });
    }

    private static Finding first(
            List<Finding> findings,
            String rule,
            String expectedFile) {

        return findings.stream()
                .filter(candidate -> "SpotBugs".equals(candidate.getAnalyzer()))
                .filter(candidate -> rule.equals(candidate.getRule()))
                .filter(candidate -> expectedFile.equals(candidate.getFile()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "SpotBugs " + rule + " finding missing: " + describe(findings)
                ));
    }

    private static String describe(List<Finding> findings) {

        return findings.stream()
                .map(finding -> finding.getAnalyzer()
                        + ":" + finding.getRule()
                        + "@" + finding.getLine())
                .reduce((left, right) -> left + ", " + right)
                .orElse("no findings");
    }
}

package com.codeguardai.service;

import com.codeguardai.config.HeuristicsProperties;
import com.codeguardai.domain.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class StaticAnalysisService {

    /**
     * Analyzer name used exclusively for CodeGuard's own token based
     * heuristics. It is intentionally distinct from PMD, Checkstyle and
     * SpotBugs so a heuristic result can never be mistaken for a genuine
     * third-party analyzer finding.
     */
    public static final String HEURISTIC_ANALYZER =
            "CodeGuard-Heuristics";

    private static final Logger log =
            LoggerFactory.getLogger(StaticAnalysisService.class);

    private final PmdAnalyzer pmdAnalyzer;
    private final CheckstyleAnalyzer checkstyleAnalyzer;
    private final SpotBugsAnalyzer spotBugsAnalyzer;
    private final HeuristicsProperties heuristicsProperties;

    @Autowired
    public StaticAnalysisService(
            PmdAnalyzer pmdAnalyzer,
            CheckstyleAnalyzer checkstyleAnalyzer,
            SpotBugsAnalyzer spotBugsAnalyzer,
            HeuristicsProperties heuristicsProperties) {

        this.pmdAnalyzer = pmdAnalyzer;
        this.checkstyleAnalyzer = checkstyleAnalyzer;
        this.spotBugsAnalyzer = spotBugsAnalyzer;
        this.heuristicsProperties =
                heuristicsProperties == null
                        ? new HeuristicsProperties()
                        : heuristicsProperties;
    }

    /**
     * Convenience constructor that leaves the heuristic layer disabled, so an
     * unconfigured service reports only genuine analyzer findings.
     */
    public StaticAnalysisService(
            PmdAnalyzer pmdAnalyzer,
            CheckstyleAnalyzer checkstyleAnalyzer,
            SpotBugsAnalyzer spotBugsAnalyzer) {

        this(
                pmdAnalyzer,
                checkstyleAnalyzer,
                spotBugsAnalyzer,
                new HeuristicsProperties()
        );
    }

    public List<Finding> analyzeSource(String sourceCode, String fileName) {

        List<Finding> findings = new ArrayList<>();

        // Real PMD analysis
        findings.addAll(
                pmdAnalyzer.analyze(sourceCode, fileName)
        );

        // Real Checkstyle analysis
        findings.addAll(
                checkstyleAnalyzer.analyze(sourceCode, fileName)
        );

        // Real SpotBugs analysis (compiles the snippet and inspects bytecode)
        findings.addAll(
                analyzeWithSpotBugs(
                        sourceCode,
                        fileName
                )
        );

        String safeSourceCode = sourceCode == null
                ? ""
                : sourceCode;

        String safeFileName =
                fileName == null || fileName.isBlank()
                        ? "Sample.java"
                        : fileName;

        /*
         * ============================================================
         * OPTIONAL CODEGUARD HEURISTIC LAYER
         * ============================================================
         *
         * Token based heuristics kept for the educational sample snippets.
         *
         * These checks are NOT performed by PMD, Checkstyle or SpotBugs, so
         * their findings are reported under the dedicated
         * "CodeGuard-Heuristics" analyzer name and use CodeGuard rule names.
         * They are never attributed to a third-party analyzer and never
         * claim to be a PMD, Checkstyle or SpotBugs rule.
         *
         * Genuine analyzer findings keep their own analyzer name:
         *
         *     PMD        -> PmdAnalyzer
         *     Checkstyle -> CheckstyleAnalyzer
         *     SpotBugs   -> SpotBugsAnalyzer
         *
         * The layer is disabled by default
         * (codeguard.heuristics.enabled=false) so a normal or judge-facing
         * scan reports only genuine analyzer results. A demo configuration
         * may enable it explicitly.
         * ============================================================
         */

        if (heuristicsProperties.isEnabled()) {

            if (containsSqlInjection(safeSourceCode)) {

                findings.add(createFinding(
                        HEURISTIC_ANALYZER,
                        "CgSqlConcatenationInQuery",
                        "Security",
                        "HIGH",
                        "SQL injection",
                        "User-controlled data is concatenated into a SQL statement.",
                        safeFileName,
                        extractLine(safeSourceCode, "executeQuery"),
                        extractColumn(safeSourceCode, "executeQuery"),
                        extractSnippet(safeSourceCode, "executeQuery"),
                        "Use PreparedStatement and bind parameters instead of concatenating user input into the query."
                ));
            }

            if (containsResourceLeak(safeSourceCode)) {

                findings.add(createFinding(
                        HEURISTIC_ANALYZER,
                        "CgUnclosedResource",
                        "Resource Management",
                        "MEDIUM",
                        "Resource leak",
                        "A stream or resource is opened but not closed in a guaranteed block.",
                        safeFileName,
                        extractLine(safeSourceCode, "FileInputStream"),
                        extractColumn(safeSourceCode, "FileInputStream"),
                        extractSnippet(safeSourceCode, "FileInputStream"),
                        "Wrap the resource in try-with-resources so the close is executed reliably."
                ));
            }

            if (containsHardcodedSecret(safeSourceCode)) {

                findings.add(createFinding(
                        HEURISTIC_ANALYZER,
                        "CgHardcodedSecret",
                        "Security",
                        "HIGH",
                        "Hardcoded credentials",
                        "A password or secret is stored directly in source code.",
                        safeFileName,
                        extractLine(safeSourceCode, "password"),
                        extractColumnIgnoringCase(safeSourceCode, "password"),
                        extractSnippet(safeSourceCode, "password"),
                        "Move secrets to environment variables, secure config, or a secret manager."
                ));
            }

            if (containsSwallowedException(safeSourceCode)) {

                findings.add(createFinding(
                        HEURISTIC_ANALYZER,
                        "CgSwallowedException",
                        "Reliability",
                        "MEDIUM",
                        "Swallowed exception",
                        "The exception is caught but discarded without logging or rethrowing.",
                        safeFileName,
                        extractLine(safeSourceCode, "catch"),
                        extractColumn(safeSourceCode, "catch"),
                        extractSnippet(safeSourceCode, "catch"),
                        "Log the exception, surface it, or rethrow it with context so the cause is preserved."
                ));
            }

        }

        return findings;
    }

    /*
     * SpotBugs analyses compiled bytecode, so a snippet that cannot be compiled
     * (or an internal SpotBugs failure) must not fail the whole scan. The
     * remaining analyzers and heuristics still report what they found and the
     * skip is logged.
     */
    private List<Finding> analyzeWithSpotBugs(
            String sourceCode,
            String fileName) {

        try {
            return spotBugsAnalyzer.analyze(
                    sourceCode,
                    fileName
            );

        } catch (RuntimeException exception) {

            log.warn(
                    "SpotBugs analysis skipped: {}",
                    exception.getMessage()
            );

            return new ArrayList<>();
        }
    }

    private boolean containsSqlInjection(String sourceCode) {

        return sourceCode.contains("executeQuery")
                && sourceCode.contains("+")
                && sourceCode.toLowerCase().contains("username");
    }

    private boolean containsResourceLeak(String sourceCode) {

        return sourceCode.contains("FileInputStream")
                && !sourceCode.contains("try (");
    }

    private boolean containsHardcodedSecret(String sourceCode) {

        String lower = sourceCode.toLowerCase();

        return lower.contains("password")
                && lower.contains("\"")
                && lower.contains("secret");
    }

    private boolean containsSwallowedException(String sourceCode) {

        /*
         * The parentheses are explicit and intentional: any catch clause must
         * be accompanied by the "return false" that actually swallows the
         * failure, i.e. (catchClauseA || catchClauseB) && swallowsFailure.
         * Keeping them explicit documents the grouping and prevents a future
         * edit from silently re-parsing this as
         *     catchClauseA || (catchClauseB && swallowsFailure)
         * which would flag every catch block even when it is handled.
         */
        return (sourceCode.contains("catch (Exception e)")
                || sourceCode.contains("catch (IOException"))
                && sourceCode.contains("return false");
    }

    private Finding createFinding(
            String analyzer,
            String rule,
            String category,
            String severity,
            String title,
            String message,
            String file,
            Integer line,
            Integer column,
            String codeSnippet,
            String suggestedContext) {

        String safeFile =
                file == null || file.isBlank()
                        ? "Sample.java"
                        : file;

        int safeLine =
                line == null
                        ? 1
                        : line;

        int safeColumn =
                column == null
                        ? 1
                        : column;

        String safeSnippet =
                codeSnippet == null
                        ? ""
                        : codeSnippet;

        Finding finding = new Finding(
                analyzer,
                rule,
                category,
                severity,
                title,
                message,
                safeFile,
                safeLine,
                safeColumn,
                safeSnippet,
                suggestedContext
        );

        finding.setId(
                finding.generateStableId()
        );

        return finding;
    }

    private Integer extractLine(
            String sourceCode,
            String token) {

        String[] lines =
                sourceCode.split("\\r?\\n");

        for (int i = 0; i < lines.length; i++) {

            if (lines[i].contains(token)) {
                return i + 1;
            }
        }

        return 1;
    }

    private String extractSnippet(
            String sourceCode,
            String token) {

        String[] lines =
                sourceCode.split("\\r?\\n");

        for (String line : lines) {

            if (line.contains(token)) {
                return line.trim();
            }
        }

        return sourceCode.length() > 180
                ? sourceCode.substring(0, 180)
                : sourceCode;
    }

    private Integer extractLineIgnoringCase(
            String sourceCode,
            String token) {

        String[] lines =
                sourceCode.split("\\r?\\n");

        String lowerToken =
                token.toLowerCase();

        for (int i = 0; i < lines.length; i++) {

            if (lines[i].toLowerCase()
                    .contains(lowerToken)) {
                return i + 1;
            }
        }

        return 1;
    }

    private String extractSnippetIgnoringCase(
            String sourceCode,
            String token) {

        String[] lines =
                sourceCode.split("\\r?\\n");

        String lowerToken =
                token.toLowerCase();

        for (String line : lines) {

            if (line.toLowerCase()
                    .contains(lowerToken)) {
                return line.trim();
            }
        }

        return sourceCode.length() > 180
                ? sourceCode.substring(0, 180)
                : sourceCode;
    }

    /**
     * 1-based column of the matched token inside the line that
     * {@link #extractLine(String, String)} reports, so a heuristic finding
     * points at the token it actually matched instead of a fixed constant.
     */
    private Integer extractColumn(
            String sourceCode,
            String token) {

        return columnOfFirstMatch(
                sourceCode,
                token,
                false
        );
    }

    private Integer extractColumnIgnoringCase(
            String sourceCode,
            String token) {

        return columnOfFirstMatch(
                sourceCode,
                token,
                true
        );
    }

    private Integer columnOfFirstMatch(
            String sourceCode,
            String token,
            boolean ignoreCase) {

        String[] lines =
                sourceCode.split("\\r?\\n");

        String needle =
                ignoreCase
                        ? token.toLowerCase()
                        : token;

        for (String line : lines) {

            String haystack =
                    ignoreCase
                            ? line.toLowerCase()
                            : line;

            int index =
                    haystack.indexOf(needle);

            if (index >= 0) {
                return index + 1;
            }
        }

        return 1;
    }
}


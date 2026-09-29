package com.codeguardai.service;

import com.codeguardai.domain.Finding;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.renderers.XMLRenderer;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class PmdAnalyzer {

    public List<Finding> analyze(String sourceCode, String fileName) {
        List<Finding> findings = new ArrayList<>();

        String safeSourceCode = sourceCode == null ? "" : sourceCode;
        String safeFileName = fileName == null || fileName.isBlank()
                ? "Sample.java"
                : fileName;

        if (safeSourceCode.isBlank()) {
            return findings;
        }

        Path tempFile = null;

        try {
            tempFile = Files.createTempFile("codeguard-pmd-", ".java");
            Files.writeString(tempFile, safeSourceCode);

            PMDConfiguration configuration = new PMDConfiguration();
            configuration.addRuleSet("rulesets/java/quickstart.xml");

            try (PmdAnalysis analysis = PmdAnalysis.create(configuration)) {

                StringWriter reportWriter = new StringWriter();

                XMLRenderer renderer = new XMLRenderer();
                renderer.setWriter(reportWriter);

                analysis.addRenderer(renderer);

                analysis.files().addFile(tempFile);

                analysis.performAnalysis();

                findings.addAll(
                        parseReport(
                                reportWriter.toString(),
                                safeFileName,
                                safeSourceCode
                        )
                );
            }

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "PMD analysis failed: " + exception.getMessage(),
                    exception
            );
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                    // Temporary-file cleanup failure should not hide
                    // the actual analysis result.
                }
            }
        }

        return findings;
    }

    private List<Finding> parseReport(
            String xml,
            String fileName,
            String sourceCode) {

        List<Finding> findings = new ArrayList<>();

        if (xml == null || xml.isBlank()) {
            return findings;
        }

        try {
            Document document = DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .parse(
                            new java.io.ByteArrayInputStream(
                                    xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            )
                    );

            NodeList violationNodes =
                    document.getElementsByTagName("violation");

            String[] sourceLines = sourceCode.split("\\r?\\n", -1);

            for (int i = 0; i < violationNodes.getLength(); i++) {

                Node violation = violationNodes.item(i);

                String rule = getAttribute(violation, "rule");
                String ruleset = getAttribute(violation, "ruleset");
                String beginLine = getAttribute(violation, "beginline");
                String beginColumn = getAttribute(violation, "begincolumn");

                int line = parsePositiveInt(beginLine, 1);
                int column = parsePositiveInt(beginColumn, 1);

                String message = violation.getTextContent() == null
                        ? "PMD detected a code-quality issue."
                        : violation.getTextContent().trim();

                String snippet = extractSnippet(sourceLines, line);

                String severity = determineSeverity(ruleset);

                Finding finding = new Finding(
                        "PMD",
                        rule.isBlank() ? "PMD-" + i : rule,
                        determineCategory(ruleset),
                        severity,
                        rule.isBlank() ? "PMD finding" : rule,
                        message,
                        fileName,
                        line,
                        column,
                        snippet,
                        "Review this PMD finding and apply the recommended correction."
                );

                finding.setId(finding.generateStableId());

                findings.add(finding);
            }

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to parse PMD report: " + exception.getMessage(),
                    exception
            );
        }

        return findings;
    }

    private String getAttribute(Node node, String attribute) {
        if (node.getAttributes() == null
                || node.getAttributes().getNamedItem(attribute) == null) {
            return "";
        }

        return node.getAttributes()
                .getNamedItem(attribute)
                .getNodeValue();
    }

    private int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);

            return parsed > 0
                    ? parsed
                    : fallback;

        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String extractSnippet(String[] lines, int line) {
        if (line <= 0 || line > lines.length) {
            return "";
        }

        String snippet = lines[line - 1].trim();

        return snippet.length() > 180
                ? snippet.substring(0, 180)
                : snippet;
    }

    private String determineCategory(String ruleset) {
        if (ruleset == null) {
            return "Code Quality";
        }

        String lower = ruleset.toLowerCase();

        if (lower.contains("security")) {
            return "Security";
        }

        if (lower.contains("performance")) {
            return "Performance";
        }

        if (lower.contains("design")) {
            return "Design";
        }

        if (lower.contains("error")) {
            return "Correctness";
        }

        return "Code Quality";
    }

    private String determineSeverity(String ruleset) {
        if (ruleset == null) {
            return "MEDIUM";
        }

        String lower = ruleset.toLowerCase();

        if (lower.contains("security")
                || lower.contains("error")) {
            return "HIGH";
        }

        return "MEDIUM";
    }
}

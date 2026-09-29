package com.codeguardai.service;

import com.codeguardai.domain.Finding;
import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.DefaultConfiguration;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class CheckstyleAnalyzer {

    public List<Finding> analyze(String sourceCode, String fileName) {

        List<Finding> findings = new ArrayList<>();

        String safeSourceCode =
                sourceCode == null
                        ? ""
                        : sourceCode;

        String safeFileName =
                fileName == null || fileName.isBlank()
                        ? "Sample.java"
                        : fileName;

        if (safeSourceCode.isBlank()) {
            return findings;
        }

        Path tempFile = null;

        try {
            tempFile = Files.createTempFile(
                    "codeguard-checkstyle-",
                    ".java"
            );

            Files.writeString(
                    tempFile,
                    safeSourceCode
            );

            Configuration configuration =
                    createConfiguration();

            Checker checker = new Checker();

            checker.setModuleClassLoader(
                    Thread.currentThread()
                            .getContextClassLoader()
            );

            checker.configure(configuration);

            checker.addListener(
                    new CheckstyleListener(
                            findings,
                            safeFileName,
                            safeSourceCode
                    )
            );

            checker.process(
                    List.of(tempFile.toFile())
            );

            checker.destroy();

        } catch (Exception exception) {

            throw new IllegalStateException(
                    "Checkstyle analysis failed: "
                            + exception.getMessage(),
                    exception
            );

        } finally {

            if (tempFile != null) {

                try {
                    Files.deleteIfExists(tempFile);

                } catch (Exception ignored) {
                    // Do not hide the analysis result.
                }
            }
        }

        return findings;
    }

    private Configuration createConfiguration() {

        DefaultConfiguration checker =
                new DefaultConfiguration("Checker");

        DefaultConfiguration lineLength =
                new DefaultConfiguration("LineLength");

        lineLength.addProperty(
                "max",
                "120"
        );

        checker.addChild(lineLength);

        DefaultConfiguration treeWalker =
                new DefaultConfiguration("TreeWalker");

        DefaultConfiguration avoidStarImport =
                new DefaultConfiguration("AvoidStarImport");

        treeWalker.addChild(avoidStarImport);

        checker.addChild(treeWalker);

        return checker;
    }

    private static class CheckstyleListener
            implements AuditListener {

        private final List<Finding> findings;
        private final String fileName;
        private final String sourceCode;

        private CheckstyleListener(
                List<Finding> findings,
                String fileName,
                String sourceCode) {

            this.findings = findings;
            this.fileName = fileName;
            this.sourceCode = sourceCode;
        }

        @Override
        public void auditStarted(AuditEvent event) {
        }

        @Override
        public void auditFinished(AuditEvent event) {
        }

        @Override
        public void fileStarted(AuditEvent event) {
        }

        @Override
        public void fileFinished(AuditEvent event) {
        }

        @Override
        public void addError(AuditEvent event) {

            int line = event.getLine();
            int column = event.getColumn();

            if (line <= 0) {
                line = 1;
            }

            if (column <= 0) {
                column = 1;
            }

            String rule =
                    event.getSourceName() == null
                            ? "Checkstyle"
                            : event.getSourceName();

            String message =
                    event.getMessage() == null
                            ? "Checkstyle detected a code-quality issue."
                            : event.getMessage();

            String snippet =
                    extractSnippet(
                            sourceCode,
                            line
                    );

            Finding finding = new Finding(
                    "Checkstyle",
                    rule,
                    "Code Quality",
                    "MEDIUM",
                    rule,
                    message,
                    fileName,
                    line,
                    column,
                    snippet,
                    "Review the Checkstyle finding and apply the recommended correction."
            );

            finding.setId(
                    finding.generateStableId()
            );

            findings.add(finding);
        }

        @Override
        public void addException(
                AuditEvent event,
                Throwable throwable) {
        }

        private String extractSnippet(
                String source,
                int line) {

            String[] lines =
                    source.split(
                            "\\r?\\n",
                            -1
                    );

            if (line < 1 || line > lines.length) {
                return "";
            }

            String snippet =
                    lines[line - 1].trim();

            return snippet.length() > 180
                    ? snippet.substring(0, 180)
                    : snippet;
        }
    }
}
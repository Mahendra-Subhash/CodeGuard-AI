
package com.codeguardai.service;

import com.codeguardai.domain.Finding;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.FindBugs2;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.Project;
import edu.umd.cs.findbugs.XMLBugReporter;
import edu.umd.cs.findbugs.config.UserPreferences;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SpotBugsAnalyzer {

    /*
     * javac requires a source file to be named after the public type it
     * declares, so the submitted snippet is compiled under that name while
     * findings are still reported against the caller supplied file name.
     */
    private static final Pattern PUBLIC_TYPE_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*public\\s+(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+|strictfp\\s+)*"
                            + "(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
            );

    public List<Finding> analyze(
            String sourceCode,
            String fileName) {

        List<Finding> findings =
                new ArrayList<>();

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

        Path tempDirectory = null;

        try {
            tempDirectory =
                    Files.createTempDirectory(
                            "codeguard-spotbugs-"
                    );

            Path sourceFile =
                    tempDirectory.resolve(
                            resolveCompilationFileName(safeSourceCode, safeFileName)
                    );

            Files.writeString(
                    sourceFile,
                    safeSourceCode,
                    StandardCharsets.UTF_8
            );

            Path classesDirectory =
                    tempDirectory.resolve("classes");

            Files.createDirectories(
                    classesDirectory
            );

            compileSource(
                    sourceFile,
                    classesDirectory
            );

            Path reportFile =
                    tempDirectory.resolve(
                            "spotbugs.xml"
                    );

            runSpotBugs(
                    classesDirectory,
                    reportFile
            );

            findings.addAll(
                    parseReport(
                            reportFile,
                            safeFileName,
                            safeSourceCode
                    )
            );

        } catch (Exception exception) {

            throw new IllegalStateException(
                    "SpotBugs analysis failed: "
                            + exception.getMessage(),
                    exception
            );

        } finally {

            deleteDirectory(
                    tempDirectory
            );
        }

        return findings;
    }

    private void compileSource(
            Path sourceFile,
            Path classesDirectory) {

        JavaCompiler compiler =
                ToolProvider.getSystemJavaCompiler();

        if (compiler == null) {
            throw new IllegalStateException(
                    "JDK compiler is not available."
            );
        }

        int result =
                compiler.run(
                        null,
                        null,
                        null,
                        "-g",
                        "-d",
                        classesDirectory.toString(),
                        sourceFile.toString()
                );

        if (result != 0) {
            throw new IllegalStateException(
                    "Submitted Java source could not be compiled."
            );
        }
    }

    @SuppressWarnings("resource")
    private void runSpotBugs(
            Path classesDirectory,
            Path reportFile)
            throws Exception {

        Project project =
                new Project();

        project.addFile(
                classesDirectory.toString()
        );

        UserPreferences preferences =
                UserPreferences
                        .createDefaultUserPreferences();

        FindBugs2 findBugs =
                new FindBugs2();

        XMLBugReporter reporter =
                new XMLBugReporter(project);

        reporter.setAddMessages(
                true
        );

        // SpotBugs silently discards every finding until a priority threshold is
        // configured: accumulator based detectors filter on
        // AbstractBugReporter.getPriorityThreshold() (which defaults to 0) and
        // direct reporters throw IllegalStateException("Priority threshold not
        // set"). Reporting all priorities keeps the LOW severity findings that
        // mapSeverity() already knows how to classify.
        reporter.setPriorityThreshold(
                Priorities.LOW_PRIORITY
        );

        findBugs.setProject(
                project
        );

        findBugs.setUserPreferences(
                preferences
        );

        findBugs.setDetectorFactoryCollection(
                DetectorFactoryCollection.instance()
        );

        findBugs.setBugReporter(
                reporter
        );

        findBugs.setNoClassOk(
                true
        );

        try (PrintStream printStream =
                     new PrintStream(
                             Files.newOutputStream(
                                     reportFile
                             ),
                             true,
                             StandardCharsets.UTF_8
                     )) {

            reporter.setOutputStream(
                    printStream
            );

            findBugs.execute();

            reporter.finish();

        } finally {

            findBugs.dispose();
        }
    }

    private List<Finding> parseReport(
            Path reportFile,
            String fileName,
            String sourceCode)
            throws Exception {

        List<Finding> findings =
                new ArrayList<>();

        if (!Files.exists(reportFile)) {
            return findings;
        }

        String xml =
                Files.readString(
                        reportFile,
                        StandardCharsets.UTF_8
                );

        if (xml.isBlank()) {
            return findings;
        }

        Document document =
                javax.xml.parsers
                        .DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(
                                new ByteArrayInputStream(
                                        xml.getBytes(
                                                StandardCharsets.UTF_8
                                        )
                                )
                        );

        NodeList bugNodes =
                document.getElementsByTagName(
                        "BugInstance"
                );

        for (int i = 0;
             i < bugNodes.getLength();
             i++) {

            Node bug =
                    bugNodes.item(i);

            String type =
                    getAttribute(
                            bug,
                            "type"
                    );

            String category =
                    getAttribute(
                            bug,
                            "category"
                    );

            String priority =
                    getAttribute(
                            bug,
                            "priority"
                    );

            String message =
                    extractMessage(
                            bug,
                            type
                    );

            int line =
                    extractLine(
                            bug
                    );

            String snippet =
                    extractSnippet(
                            sourceCode,
                            line
                    );

            Finding finding =
                    new Finding(
                            "SpotBugs",
                            type.isBlank()
                                    ? "SpotBugs"
                                    : type,
                            category.isBlank()
                                    ? "Correctness"
                                    : category,
                            mapSeverity(
                                    priority
                            ),
                            type.isBlank()
                                    ? "SpotBugs finding"
                                    : type,
                            message,
                            fileName,
                            line,
                            1,
                            snippet,
                            "Review the SpotBugs finding and apply the recommended correction."
                    );

            finding.setId(
                    finding.generateStableId()
            );

            findings.add(
                    finding
            );
        }

        return findings;
    }

    private String extractMessage(
            Node bug,
            String fallbackType) {

        NodeList messageNodes =
                ((org.w3c.dom.Element) bug)
                        .getElementsByTagName(
                                "LongMessage"
                        );

        if (messageNodes.getLength() > 0) {

            String message =
                    messageNodes.item(0)
                            .getTextContent();

            if (message != null
                    && !message.isBlank()) {

                return message.trim();
            }
        }

        return fallbackType == null
                || fallbackType.isBlank()
                ? "SpotBugs detected a potential issue."
                : fallbackType;
    }

    private int extractLine(
            Node bug) {

        NodeList sourceLines =
                ((org.w3c.dom.Element) bug)
                        .getElementsByTagName(
                                "SourceLine"
                        );

        if (sourceLines.getLength() == 0) {
            return 1;
        }

        // SpotBugs nests a SourceLine inside every Class/Method/Field element
        // that it reports. Those nested entries point at the enclosing
        // declaration (usually line 1), so prefer the SourceLine that is a
        // direct child of the BugInstance, falling back to the last (most
        // specific) nested entry when no direct child is present.
        Node preciseSourceLine =
                findDirectChild(
                        bug,
                        "SourceLine"
                );

        if (preciseSourceLine == null) {
            preciseSourceLine =
                    sourceLines.item(
                            sourceLines.getLength() - 1
                    );
        }

        String line =
                getAttribute(
                        preciseSourceLine,
                        "start"
                );

        try {

            int parsed =
                    Integer.parseInt(line);

            return parsed > 0
                    ? parsed
                    : 1;

        } catch (NumberFormatException exception) {

            return 1;
        }
    }

    private Node findDirectChild(
            Node parent,
            String nodeName) {

        NodeList children =
                parent.getChildNodes();

        for (int i = children.getLength() - 1; i >= 0; i--) {

            Node child =
                    children.item(i);

            if (nodeName.equals(
                    child.getNodeName()
            )) {
                return child;
            }
        }

        return null;
    }

    private String extractSnippet(
            String sourceCode,
            int line) {

        String[] lines =
                sourceCode.split(
                        "\\r?\\n",
                        -1
                );

        if (line <= 0
                || line > lines.length) {

            return "";
        }

        String snippet =
                lines[line - 1].trim();

        return snippet.length() > 180
                ? snippet.substring(
                        0,
                        180
                )
                : snippet;
    }

    private String getAttribute(
            Node node,
            String attribute) {

        if (node.getAttributes() == null
                || node.getAttributes()
                .getNamedItem(attribute) == null) {

            return "";
        }

        return node.getAttributes()
                .getNamedItem(attribute)
                .getNodeValue();
    }

    private String mapSeverity(
            String priority) {

        return switch (priority) {
            case "1" -> "HIGH";
            case "2" -> "MEDIUM";
            case "3" -> "LOW";
            default -> "MEDIUM";
        };
    }

    /*
     * Resolves the file name the snippet has to be compiled under.
     *
     * The name of the public class, interface, enum or record declared by the
     * snippet always wins because javac rejects a public type whose file name
     * differs. When the snippet declares no public type the caller supplied
     * name is used (without any directory part), defaulting to Sample.java.
     */
    private String resolveCompilationFileName(
            String sourceCode,
            String fileName) {

        Matcher matcher =
                PUBLIC_TYPE_PATTERN.matcher(
                        sourceCode
                );

        if (matcher.find()) {
            return matcher.group(1) + ".java";
        }

        String safeName =
                fileName == null
                        ? ""
                        : fileName.trim();

        int separator =
                Math.max(
                        safeName.lastIndexOf('/'),
                        safeName.lastIndexOf('\\')
                );

        if (separator >= 0) {
            safeName = safeName.substring(separator + 1);
        }

        if (safeName.isBlank()) {
            return "Sample.java";
        }

        return safeName.endsWith(".java")
                ? safeName
                : safeName + ".java";
    }

    private void deleteDirectory(
            Path directory) {

        if (directory == null) {
            return;
        }

        try {

            Files.walk(directory)
                    .sorted(
                            Comparator.reverseOrder()
                    )
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(
                                            path
                                    );
                                } catch (Exception ignored) {
                                }
                            }
                    );

        } catch (Exception ignored) {
        }
    }
}

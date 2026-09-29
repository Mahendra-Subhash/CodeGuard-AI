package com.codeguardai.service;

import com.codeguardai.ai.AiAnalysisRequest;
import com.codeguardai.ai.AiAnalysisResponse;
import com.codeguardai.ai.AiInferenceProvider;
import com.codeguardai.domain.Finding;
import com.codeguardai.domain.FixApplicationResult;
import com.codeguardai.domain.FixProposal;
import com.codeguardai.domain.Scan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that a re-scan analyzes the source supplied by the dashboard (the code
 * currently open in the editor) instead of the source stored by the previous
 * scan, while the original baseline used for verification is never rewritten.
 */
class ScanServiceRescanTest {

    private static final String STORED_SOURCE = """
            public class StoredDemo {
                public String read(String value) {
                    return value.trim();
                }
            }
            """;

    private static final String EDITED_SOURCE = """
            public class EditedDemo {
                public String read(String value) {
                    return value == null ? "" : value.trim();
                }
            }
            """;

    /** Line and text of the single verified line inside both sources. */
    private static final int TARGET_LINE = 3;

    private static final String EDITED_LINE_TEXT =
            "return value == null ? \"\" : value.trim();";

    @Test
    void rescanAnalyzesTheSuppliedEditorSource() {

        ScanService service = createService();

        Scan scan = service.createScan(
                STORED_SOURCE,
                "StoredDemo.java"
        );

        List<String> baselineIds = idsOf(scan.getBaselineFindings());

        Scan rescanned = service.rescan(
                scan.getId(),
                EDITED_SOURCE,
                "EditedDemo.java"
        );

        /*
         * The scan now describes the code from the editor.
         */
        assertThat(rescanned.getSourceCode())
                .isEqualTo(EDITED_SOURCE);

        assertThat(rescanned.getFileName())
                .isEqualTo("EditedDemo.java");

        assertThat(rescanned.getFindings())
                .isNotEmpty();

        assertThat(rescanned.getFindings())
                .anyMatch(finding ->
                        snippetOf(finding).contains("EditedDemo"));

        assertThat(rescanned.getFindings())
                .noneMatch(finding ->
                        snippetOf(finding).contains("StoredDemo"));

        assertThat(service.getScan(scan.getId()).getSourceCode())
                .isEqualTo(EDITED_SOURCE);

        /*
         * The baseline for verification still describes the ORIGINAL source.
         */
        assertThat(idsOf(rescanned.getBaselineFindings()))
                .isEqualTo(baselineIds);

        assertThat(rescanned.getBaselineFindings())
                .anyMatch(finding ->
                        snippetOf(finding).contains("StoredDemo"));

        assertThat(rescanned.getStatus())
                .isEqualTo("rescanned-with-findings");
    }

    @Test
    void rescanWithoutASourceKeepsTheStoredSource() {

        ScanService service = createService();

        Scan scan = service.createScan(
                STORED_SOURCE,
                "StoredDemo.java"
        );

        Scan rescanned = service.rescan(scan.getId());

        assertThat(rescanned.getSourceCode())
                .isEqualTo(STORED_SOURCE);

        assertThat(rescanned.getFindings())
                .anyMatch(finding ->
                        snippetOf(finding).contains("StoredDemo"));
    }

    @Test
    void rescanIgnoresBlankSourceAndFileName() {

        ScanService service = createService();

        Scan scan = service.createScan(
                STORED_SOURCE,
                "StoredDemo.java"
        );

        Scan rescanned = service.rescan(
                scan.getId(),
                "   ",
                "  "
        );

        assertThat(rescanned.getSourceCode())
                .isEqualTo(STORED_SOURCE);

        assertThat(rescanned.getFileName())
                .isEqualTo("StoredDemo.java");
    }

    @Test
    void aFixScopedToOneLineIsStillAppliedAfterAnEditorRescan() {

        ScanService service = createService();

        Scan scan = service.createScan(
                STORED_SOURCE,
                "StoredDemo.java"
        );

        List<String> baselineIds = idsOf(scan.getBaselineFindings());

        Scan rescanned = service.rescan(
                scan.getId(),
                EDITED_SOURCE,
                "EditedDemo.java"
        );

        Finding finding = new Finding(
                "CodeGuard-Test",
                "CG-UnsafeDereference",
                "Reliability",
                "HIGH",
                "Controlled test finding",
                "Controlled finding used to verify the rescan apply-fix contract.",
                "EditedDemo.java",
                TARGET_LINE,
                1,
                EDITED_LINE_TEXT,
                "Guard the value before dereferencing it."
        );

        rescanned.setFindings(
                new ArrayList<>(
                        List.of(finding)
                )
        );

        String replacement =
                "        return value == null || value.isBlank() ? \"\" : value.trim();";

        FixApplicationResult result =
                service.applyFixToFinding(
                        finding.getId(),
                        replacement
                );

        assertThat(result.applied()).isTrue();
        assertThat(result.status()).isEqualTo("applied");
        assertThat(result.startLine()).isEqualTo(TARGET_LINE);
        assertThat(result.endLine()).isEqualTo(TARGET_LINE);

        assertThat(result.scan().getSourceCode())
                .contains("value.isBlank()")
                .doesNotContain("return value == null ? \"\" : value.trim();");

        /*
         * Applying a fix must not touch the original baseline either.
         */
        assertThat(idsOf(result.scan().getBaselineFindings()))
                .isEqualTo(baselineIds);
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

    private static List<String> idsOf(List<Finding> findings) {

        return findings.stream()
                .map(Finding::getId)
                .toList();
    }

    private static String snippetOf(Finding finding) {

        return finding.getCodeSnippet() == null
                ? ""
                : finding.getCodeSnippet();
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

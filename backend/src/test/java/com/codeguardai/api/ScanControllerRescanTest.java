package com.codeguardai.api;

import com.codeguardai.ai.AiAnalysisRequest;
import com.codeguardai.ai.AiAnalysisResponse;
import com.codeguardai.ai.AiInferenceProvider;
import com.codeguardai.domain.FixProposal;
import com.codeguardai.domain.Scan;
import com.codeguardai.service.CheckstyleAnalyzer;
import com.codeguardai.service.PmdAnalyzer;
import com.codeguardai.service.SafeFixApplier;
import com.codeguardai.service.ScanService;
import com.codeguardai.service.SpotBugsAnalyzer;
import com.codeguardai.service.StaticAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The re-scan endpoint must analyze the source the dashboard sends it (the
 * current editor contents) and must keep working without a request body.
 */
class ScanControllerRescanTest {

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

    @Test
    void rescanWithoutABodyKeepsTheStoredSource() {

        ScanService service = createService();

        Scan scan = service.createScan(
                STORED_SOURCE,
                "StoredDemo.java"
        );

        ResponseEntity<Scan> response =
                new ScanController(service)
                        .rescan(scan.getId(), null);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(response.getBody()).isNotNull();

        assertThat(response.getBody().getSourceCode())
                .isEqualTo(STORED_SOURCE);
    }

    @Test
    void rescanWithABodyAnalyzesTheEditorSource() {

        ScanService service = createService();

        Scan scan = service.createScan(
                STORED_SOURCE,
                "StoredDemo.java"
        );

        ScanRequest request = new ScanRequest();
        request.setSourceCode(EDITED_SOURCE);
        request.setFileName("EditedDemo.java");

        ResponseEntity<Scan> response =
                new ScanController(service)
                        .rescan(scan.getId(), request);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Scan rescanned = response.getBody();

        assertThat(rescanned).isNotNull();

        assertThat(rescanned.getSourceCode())
                .isEqualTo(EDITED_SOURCE);

        assertThat(rescanned.getFindings())
                .anyMatch(finding ->
                        String.valueOf(finding.getCodeSnippet())
                                .contains("EditedDemo"));

        assertThat(rescanned.getBaselineFindings())
                .anyMatch(finding ->
                        String.valueOf(finding.getCodeSnippet())
                                .contains("StoredDemo"));

        assertThat(service.getScan(scan.getId()).getSourceCode())
                .isEqualTo(EDITED_SOURCE);
    }

    @Test
    void rescanWithAnEmptyBodyKeepsTheStoredSource() {

        ScanService service = createService();

        Scan scan = service.createScan(
                STORED_SOURCE,
                "StoredDemo.java"
        );

        ResponseEntity<Scan> response =
                new ScanController(service)
                        .rescan(scan.getId(), new ScanRequest());

        assertThat(response.getBody()).isNotNull();

        assertThat(response.getBody().getSourceCode())
                .isEqualTo(STORED_SOURCE);
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

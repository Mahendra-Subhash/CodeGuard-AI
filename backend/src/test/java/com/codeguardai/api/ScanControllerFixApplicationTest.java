package com.codeguardai.api;

import com.codeguardai.ai.AiAnalysisRequest;
import com.codeguardai.ai.AiAnalysisResponse;
import com.codeguardai.ai.AiInferenceProvider;
import com.codeguardai.domain.Finding;
import com.codeguardai.domain.FixProposal;
import com.codeguardai.domain.Scan;
import com.codeguardai.service.CheckstyleAnalyzer;
import com.codeguardai.service.PmdAnalyzer;
import com.codeguardai.service.SafeFixApplier;
import com.codeguardai.service.ScanService;
import com.codeguardai.service.StaticAnalysisService;
import com.codeguardai.service.SpotBugsAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScanControllerFixApplicationTest {

    private static final int TARGET_LINE = 4;

    private static final String TARGET_LINE_TEXT =
            "return value.trim();";

    private static final String SOURCE = """
            public class Demo {

                public String name(String value) {
                    return value.trim();
                }
            }
            """;

    @Test
    void refusesUnsafeRangeWithConflictAndKeepsSourceUnchanged() {

        ScanService service = createService();

        ScanController controller =
                new ScanController(service);

        Finding finding =
                testFinding(TARGET_LINE, "return other.trim();");

        Scan scan =
                scanWithFinding(service, finding);

        ResponseEntity<?> response =
                controller.applyFix(
                        finding.getId(),
                        Map.of(
                                "correctedCode",
                                "return value == null ? \"\" : value.trim();"
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(response.getBody())
                .isInstanceOf(Map.class);

        Map<?, ?> body =
                (Map<?, ?>) response.getBody();

        assertThat(body.get("status"))
                .isEqualTo("requires-review");

        assertThat(body.get("sourceModified"))
                .isEqualTo(false);

        assertThat(body.get("findingId"))
                .isEqualTo(finding.getId());

        assertThat(body.get("startLine"))
                .isEqualTo(TARGET_LINE);

        assertThat(body.get("endLine"))
                .isEqualTo(TARGET_LINE);

        assertThat(String.valueOf(body.get("error")))
                .isNotBlank();

        assertThat(scan.getSourceCode())
                .isEqualTo(SOURCE);

        assertThat(service.getScan(scan.getId()).getSourceCode())
                .isEqualTo(SOURCE);
    }

    @Test
    void returnsThePatchedScanWhenTheRangeIsConfirmed() {

        ScanService service = createService();

        ScanController controller =
                new ScanController(service);

        Finding finding =
                testFinding(TARGET_LINE, TARGET_LINE_TEXT);

        Scan scan =
                scanWithFinding(service, finding);

        String replacement =
                "        return value == null ? \"\" : value.trim();";

        ResponseEntity<?> response =
                controller.applyFix(
                        finding.getId(),
                        Map.of("correctedCode", replacement)
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(response.getBody())
                .isInstanceOf(Scan.class);

        Scan patched =
                (Scan) response.getBody();

        assertThat(patched.getId())
                .isEqualTo(scan.getId());

        assertThat(patched.getSourceCode())
                .contains("public class Demo {")
                .contains("    public String name(String value) {")
                .contains(replacement)
                .doesNotContain("return value.trim();");
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
                "CG-UnsafeDereference",
                "Reliability",
                "HIGH",
                "Controlled test finding",
                "Controlled finding used to verify the apply-fix contract.",
                "Demo.java",
                line,
                1,
                snippet,
                "Guard the value before dereferencing it."
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

package com.codeguardai.ai.qualcomm;

import com.codeguardai.ai.AiAnalysisRequest;
import com.codeguardai.ai.AiAnalysisResponse;
import com.codeguardai.config.QualcommProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeCapabilityQualcommAdapterTest {

    private static final List<String> SEARCHED_LOCATIONS = List.of(
            "environment QNN_SDK_ROOT",
            "environment QAIRT_SDK_ROOT",
            "java.library.path"
    );

    @Test
    void x86_64HostNeverClaimsSnapdragonExecution() {

        QualcommRuntimeStatus status =
                adapter(new QualcommProperties(), x86Facts(false))
                        .runtimeStatus();

        assertThat(status.operatingSystem()).isNotBlank();
        assertThat(status.osVersion()).isNotBlank();
        assertThat(status.cpuArchitecture()).isEqualTo("amd64");
        assertThat(status.detectedArchitecture()).isEqualTo(QualcommHostFacts.ARCHITECTURE_X86_64);
        assertThat(status.arm64HostDetected()).isFalse();
        assertThat(status.qnnRuntimeComponentsPresent()).isFalse();
        assertThat(status.npuExecutionVerified()).isFalse();
        assertThat(status.available()).isFalse();

        assertThat(status.details())
                .contains("amd64")
                .contains("x86_64")
                .containsIgnoringCase("not verified")
                .containsIgnoringCase("no qualcomm acceleration is claimed")
                .containsIgnoringCase("does not execute models");
    }

    @Test
    void missingQualcommRuntimeIsReportedAsAbsent() {

        QualcommProperties properties = new QualcommProperties();
        properties.setEnabled(true);

        RuntimeCapabilityQualcommAdapter adapter = adapter(properties, x86Facts(false));

        QualcommRuntimeStatus status = adapter.runtimeStatus();

        assertThat(status.qnnRuntimeComponentsPresent()).isFalse();
        assertThat(status.detectedComponentLocations()).isEmpty();
        assertThat(status.searchedComponentLocations()).isNotEmpty();
        assertThat(status.npuExecutionVerified()).isFalse();
        assertThat(status.available()).isFalse();
        assertThat(adapter.isAvailable()).isFalse();

        assertThat(status.detectionNotes())
                .anyMatch(note -> note.toLowerCase().contains("no qnn"));
    }

    @Test
    void configurationAloneIsNeverReportedAsAvailable() {

        QualcommProperties properties = new QualcommProperties();
        properties.setEnabled(true);
        properties.setNpuExecutionVerified(true);
        properties.setExecutionTarget("qnn-htp-hexagon");

        QualcommRuntimeStatus status =
                adapter(properties, x86Facts(false))
                        .runtimeStatus();

        assertThat(status.configuredExecutionVerification()).isTrue();
        assertThat(status.executionTarget()).isEqualTo("qnn-htp-hexagon");
        assertThat(status.npuExecutionVerified()).isFalse();
        assertThat(status.available()).isFalse();

        assertThat(status.details())
                .containsIgnoringCase("not supported by host evidence")
                .containsIgnoringCase("deployment intent only");

        assertThat(status.detectionNotes())
                .anyMatch(note -> note.contains("npu-execution-verified=true is configured"));
    }

    @Test
    void configuredButUnavailableRuntimeIsNotReportedAsAvailable() {

        QualcommProperties properties = new QualcommProperties();
        properties.setEnabled(false);
        properties.setNpuExecutionVerified(true);

        QualcommRuntimeStatus status =
                adapter(properties, arm64Facts(true))
                        .runtimeStatus();

        assertThat(status.arm64HostDetected()).isTrue();
        assertThat(status.qnnRuntimeComponentsPresent()).isTrue();
        assertThat(status.npuExecutionVerified()).isTrue();
        assertThat(status.available()).isFalse();
        assertThat(status.details()).containsIgnoringCase("disabled by configuration");
    }

    @Test
    void verifiedSnapdragonDeploymentReportsTheRuntimeAsAvailable() {

        QualcommProperties properties = new QualcommProperties();
        properties.setEnabled(true);
        properties.setNpuExecutionVerified(true);
        properties.setDeploymentMode("snapdragon-deployment");
        properties.setExecutionTarget("qnn-htp-hexagon-npu");
        properties.setModelId("codeguard-qnn-model");

        RuntimeCapabilityQualcommAdapter adapter =
                adapter(properties, arm64Facts(true));

        QualcommRuntimeStatus status = adapter.runtimeStatus();

        assertThat(status.deploymentMode()).isEqualTo("snapdragon-deployment");
        assertThat(status.executionTarget()).isEqualTo("qnn-htp-hexagon-npu");
        assertThat(status.modelId()).isEqualTo("codeguard-qnn-model");
        assertThat(status.detectedArchitecture()).isEqualTo(QualcommHostFacts.ARCHITECTURE_ARM64);
        assertThat(status.npuExecutionVerified()).isTrue();
        assertThat(status.available()).isTrue();
        assertThat(adapter.isAvailable()).isTrue();
        assertThat(status.detectedComponentLocations())
                .containsExactly("/opt/qairt/libQnnHtp.so");
        assertThat(status.details()).containsIgnoringCase("reported as available");
    }

    @Test
    void analyzeNeverFabricatesAModelResult() {

        AiAnalysisRequest request = new AiAnalysisRequest(
                "public class Demo { void run() { value.trim(); } }",
                "Demo.java",
                "SpotBugs",
                "NP_NULL_ON_SOME_PATH",
                "Reliability",
                "HIGH",
                "A possibly null value is dereferenced without a guard.",
                "value.trim();",
                "Guard the value before dereferencing it."
        );

        AiAnalysisResponse unavailable =
                adapter(new QualcommProperties(), x86Facts(false))
                        .analyze(request);

        assertNoFabricatedResult(unavailable, request);
        assertThat(unavailable.summary()).containsIgnoringCase("no model was executed");

        QualcommProperties verifiedProperties = new QualcommProperties();
        verifiedProperties.setEnabled(true);
        verifiedProperties.setNpuExecutionVerified(true);

        AiAnalysisResponse verified =
                adapter(verifiedProperties, arm64Facts(true))
                        .analyze(request);

        assertNoFabricatedResult(verified, request);
        assertThat(verified.summary()).containsIgnoringCase("no QNN execution binding");
    }

    private static void assertNoFabricatedResult(
            AiAnalysisResponse response,
            AiAnalysisRequest request
    ) {

        assertThat(response.confidence()).isEqualTo("uncalibrated");
        assertThat(response.correctedCode()).isEqualTo(request.snippet());
        assertThat(response.explanation()).containsIgnoringCase("does not execute models");
        assertThat(response.rootCause()).isNotBlank();
        assertThat(response.risk()).containsIgnoringCase("no result");
        assertThat(response.verificationHint()).contains("Snapdragon");
    }

    private static RuntimeCapabilityQualcommAdapter adapter(
            QualcommProperties properties,
            QualcommHostFacts facts
    ) {
        return new RuntimeCapabilityQualcommAdapter(properties, () -> facts);
    }

    private static QualcommHostFacts x86Facts(boolean componentsPresent) {
        return new QualcommHostFacts(
                "Windows 11",
                "10.0",
                "amd64",
                QualcommHostFacts.ARCHITECTURE_X86_64,
                false,
                componentsPresent,
                componentsPresent ? List.of("C:\\qnn\\QnnHtp.dll") : List.of(),
                SEARCHED_LOCATIONS,
                List.of(
                        "os.name=Windows 11, os.version=10.0, os.arch=amd64 (normalized architecture x86_64)",
                        "No QNN/QAIRT/SNPE runtime components were found on this machine,"
                                + " so no Qualcomm runtime can be loaded here.",
                        "Host architecture x86_64 is not ARM64,"
                                + " so Snapdragon Hexagon NPU execution cannot be verified on this machine."
                )
        );
    }

    private static QualcommHostFacts arm64Facts(boolean componentsPresent) {
        return new QualcommHostFacts(
                "Windows 11",
                "10.0",
                "aarch64",
                QualcommHostFacts.ARCHITECTURE_ARM64,
                true,
                componentsPresent,
                componentsPresent ? List.of("/opt/qairt/libQnnHtp.so") : List.of(),
                SEARCHED_LOCATIONS,
                List.of(
                        "os.name=Windows 11, os.version=10.0, os.arch=aarch64 (normalized architecture aarch64)",
                        componentsPresent
                                ? "Qualcomm runtime components were found at: /opt/qairt/libQnnHtp.so."
                                : "No QNN/QAIRT/SNPE runtime components were found on this machine,"
                                        + " so no Qualcomm runtime can be loaded here."
                )
        );
    }
}

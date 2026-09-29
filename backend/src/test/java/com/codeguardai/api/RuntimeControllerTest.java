package com.codeguardai.api;

import com.codeguardai.ai.AiProviderHealthProbe;
import com.codeguardai.ai.qualcomm.QualcommHostFacts;
import com.codeguardai.ai.qualcomm.QualcommVerificationTiers;
import com.codeguardai.ai.qualcomm.RuntimeCapabilityQualcommAdapter;
import com.codeguardai.config.AiProperties;
import com.codeguardai.config.QualcommProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeControllerTest {

    @Test
    void runtimePayloadKeepsTheFrontendContractAndReportsFactsOnly() {

        AiProperties aiProperties = new AiProperties();
        aiProperties.setBaseUrl("http://127.0.0.1:8081");

        /*
         * The unit test must never perform a real network probe. The
         * reachable/modelServed facts are verified separately against a real
         * local provider.
         */
        aiProperties.setHealthCheckEnabled(false);

        Map<String, Object> payload =
                controller(aiProperties, new QualcommProperties(), x86Facts())
                        .runtime();

        /*
         * Keys consumed by the dashboard must stay stable.
         */
        assertThat(payload).containsKeys(
                "provider",
                "model",
                "runtime",
                "timeoutMs",
                "contextLength",
                "privacyMode",
                "aiConfigured",
                "aiProviderReachable",
                "aiModelServed",
                "qualcommDeploymentMode",
                "qualcommExecutionTarget",
                "qualcommNpuVerified",
                "qualcommAvailable",
                "qualcommDetails",
                "qualcommGeniexCliDetected",
                "qualcommVerificationTier",
                "qualcommVerificationReasons"
        );

        /*
         * With the probe disabled, availability is never asserted from
         * configuration alone.
         */
        assertThat(payload.get("aiConfigured")).isEqualTo(true);
        assertThat(payload.get("aiProviderReachable")).isEqualTo(false);
        assertThat(payload.get("aiModelServed")).isEqualTo(false);
        assertThat(payload.get("aiStatus")).isEqualTo("skipped");

        assertThat(payload.get("provider")).isEqualTo(aiProperties.getProvider());
        assertThat(payload.get("model")).isEqualTo(aiProperties.getModel());
        assertThat(payload.get("timeoutMs")).isEqualTo(aiProperties.getTimeoutMs());
        assertThat(payload.get("privacyMode")).isEqualTo(aiProperties.isPrivacyMode());

        /*
         * Factual Qualcomm capability reporting.
         */
        assertThat(payload.get("qualcommAdapter"))
                .isEqualTo(RuntimeCapabilityQualcommAdapter.ADAPTER_NAME);

        assertThat(payload.get("qualcommOs")).isEqualTo("Windows 11");
        assertThat(payload.get("qualcommCpuArchitecture")).isEqualTo("amd64");
        assertThat(payload.get("qualcommDetectedArchitecture"))
                .isEqualTo(QualcommHostFacts.ARCHITECTURE_X86_64);

        assertThat(payload.get("qualcommArm64Host")).isEqualTo(false);
        assertThat(payload.get("qualcommQnnRuntimePresent")).isEqualTo(false);
        assertThat(payload.get("qualcommNpuVerified")).isEqualTo(false);
        assertThat(payload.get("qualcommAvailable")).isEqualTo(false);

        assertThat((String) payload.get("qualcommDetails"))
                .containsIgnoringCase("not verified");

        assertThat(payload.get("qualcommDetectionNotes"))
                .isInstanceOf(List.class);
    }

    @Test
    void reportsWhyTheNpuClaimIsOrIsNotAccepted() {

        AiProperties aiProperties = new AiProperties();
        aiProperties.setBaseUrl("http://127.0.0.1:8081");
        aiProperties.setHealthCheckEnabled(false);

        /*
         * No verification bean is supplied here, so the adapter applies the
         * historical rule and must say so instead of silently reporting a
         * verified NPU.
         */
        Map<String, Object> payload =
                controller(aiProperties, new QualcommProperties(), x86Facts())
                        .runtime();

        assertThat(payload.get("qualcommVerificationTier"))
                .isEqualTo(QualcommVerificationTiers.CONFIGURATION_ONLY);
        assertThat((List<String>) payload.get("qualcommVerificationReasons"))
                .isNotEmpty();
        assertThat(payload.get("qualcommVerificationMessage")).isNotNull();
    }

    @Test
    void configurationAloneDoesNotProduceAvailabilityOnANonSnapdragonHost() {

        AiProperties aiProperties = new AiProperties();
        aiProperties.setBaseUrl("http://127.0.0.1:8081");

        QualcommProperties qualcommProperties = new QualcommProperties();
        qualcommProperties.setEnabled(true);
        qualcommProperties.setNpuExecutionVerified(true);

        Map<String, Object> payload =
                controller(aiProperties, qualcommProperties, x86Facts())
                        .runtime();

        assertThat(payload.get("qualcommConfiguredExecutionVerified")).isEqualTo(true);
        assertThat(payload.get("qualcommNpuVerified")).isEqualTo(false);
        assertThat(payload.get("qualcommAvailable")).isEqualTo(false);
    }

    private static RuntimeController controller(
            AiProperties aiProperties,
            QualcommProperties qualcommProperties,
            QualcommHostFacts facts
    ) {
        return new RuntimeController(
                aiProperties,
                new RuntimeCapabilityQualcommAdapter(qualcommProperties, () -> facts),
                new AiProviderHealthProbe(aiProperties, new ObjectMapper())
        );
    }

    private static QualcommHostFacts x86Facts() {
        return new QualcommHostFacts(
                "Windows 11",
                "10.0",
                "amd64",
                QualcommHostFacts.ARCHITECTURE_X86_64,
                false,
                false,
                List.of(),
                List.of("environment QNN_SDK_ROOT", "java.library.path"),
                List.of(
                        "os.name=Windows 11, os.version=10.0, os.arch=amd64 (normalized architecture x86_64)",
                        "No QNN/QAIRT/SNPE runtime components were found on this machine,"
                                + " so no Qualcomm runtime can be loaded here."
                )
        );
    }
}

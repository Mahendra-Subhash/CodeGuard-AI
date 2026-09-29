package com.codeguardai.ai.qualcomm;

import com.codeguardai.api.RuntimeController;
import com.codeguardai.config.QualcommProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Spring context exposes exactly one Qualcomm adapter, that the
 * runtime endpoint depends on the interface, and that the reported capability
 * status is derived from facts observed on the machine running the tests.
 */
@SpringBootTest
class QualcommRuntimeWiringTest {

    @Autowired
    private QualcommAiAdapter qualcommAiAdapter;

    @Autowired
    private RuntimeController runtimeController;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private QualcommVerification qualcommVerification;

    @Test
    void runtimeCapabilityAdapterIsTheOnlyQualcommAdapterBean() {

        assertThat(qualcommAiAdapter)
                .isInstanceOf(RuntimeCapabilityQualcommAdapter.class);

        assertThat(applicationContext.getBeansOfType(QualcommAiAdapter.class))
                .hasSize(1);

        assertThat(applicationContext.containsBean("noopQualcommAiAdapter"))
                .isFalse();
    }

    @Test
    void theActiveVerificationRequiresObservedEvidence() {

        assertThat(qualcommVerification)
                .isInstanceOf(QualcommVerificationService.class);
    }

    @Test
    void theGenieXSettingsAreBoundFromApplicationYml() {

        QualcommProperties qualcommProperties = applicationContext.getBean(QualcommProperties.class);

        assertThat(qualcommProperties.getServer().isEnabled()).isFalse();
        assertThat(qualcommProperties.getServer().getBaseUrl())
                .isEqualTo("http://127.0.0.1:18181");
        assertThat(qualcommProperties.getServer().getComputeUnit()).isEqualTo("npu");
        assertThat(qualcommProperties.getVerification().getMode()).isEqualTo("both");
        assertThat(qualcommProperties.getVerification().getRequireDevice()).isEqualTo("npu");
        assertThat(qualcommProperties.getVerification().getArtifactPath())
                .isEqualTo("artifacts/qualcomm/geniex-bench.json");
        assertThat(qualcommProperties.getVerification().isAllowRemoteDevice()).isFalse();
    }

    @Test
    void runtimeEndpointReportsMachineFactsWithoutClaimingUnverifiedExecution() {

        Map<String, Object> payload =
                runtimeController.runtime();

        assertThat(payload.get("qualcommAdapter"))
                .isEqualTo(RuntimeCapabilityQualcommAdapter.ADAPTER_NAME);

        assertThat(payload.get("qualcommOs")).isNotNull();
        assertThat(payload.get("qualcommCpuArchitecture")).isNotNull();
        assertThat(payload.get("qualcommDetectedArchitecture")).isNotNull();

        /*
         * The endpoint always explains the verdict: a tier, reason codes and the
         * evidence that was gathered.
         */
        assertThat(payload).containsKeys(
                "qualcommVerificationTier",
                "qualcommVerificationReasons",
                "qualcommVerificationMessage"
        );

        assertThat((String) payload.get("qualcommVerificationTier")).isNotBlank();
        assertThat((List<String>) payload.get("qualcommVerificationReasons")).isNotNull();

        boolean arm64Host = (Boolean) payload.get("qualcommArm64Host");
        boolean componentsPresent = (Boolean) payload.get("qualcommQnnRuntimePresent");
        boolean verified = (Boolean) payload.get("qualcommNpuVerified");
        boolean available = (Boolean) payload.get("qualcommAvailable");

        if (verified) {
            assertThat((String) payload.get("qualcommVerificationTier"))
                    .isEqualTo(QualcommVerificationTiers.NPU_VERIFIED);
        }

        if (available) {
            assertThat(verified).isTrue();
            assertThat(componentsPresent).isTrue();
            assertThat(arm64Host).isTrue();
        }

        if (!arm64Host) {
            assertThat(verified).isFalse();
            assertThat(available).isFalse();
        }
    }
}

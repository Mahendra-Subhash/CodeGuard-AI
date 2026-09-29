package com.codeguardai.ai.qualcomm;

import com.codeguardai.config.QualcommProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Verification seam for the "Snapdragon/NPU execution verified" claim.
 *
 * <p>{@link QualcommVerificationService} is the production implementation: it
 * requires observed evidence. The {@link #configurationOnly} fallback is used
 * only by deployments that supply no verification bean; it applies the historical
 * rule (ARM64 host + runtime components present + configured flag) and reports
 * the weaker {@link QualcommVerificationTiers#CONFIGURATION_ONLY} tier, so a
 * configured flag can never produce the verified tier.
 */
public interface QualcommVerification {

    /**
     * @param facts host observations gathered by the {@link QualcommRuntimeDetector}
     * @return the verification result, never {@code null}
     */
    VerificationOutcome verify(QualcommHostFacts facts);

    static QualcommVerification configurationOnly(QualcommProperties properties) {
        return facts -> {

            boolean verified = facts.arm64HostDetected()
                    && facts.qnnRuntimeComponentsPresent()
                    && properties.isNpuExecutionVerified();

            List<String> reasons = new ArrayList<>();

            if (!facts.arm64HostDetected()) {
                reasons.add(QualcommVerificationTiers.HOST_NOT_ARM64 + ":"
                        + facts.detectedArchitecture());
            }

            if (!facts.qnnRuntimeComponentsPresent()) {
                reasons.add("qnn-runtime-components-missing");
            }

            if (!properties.isNpuExecutionVerified()) {
                reasons.add("configured-verification-flag-not-set");
            }

            if (verified) {
                reasons.add(QualcommVerificationTiers.GATE_PASSED + ":host-and-configuration");
                reasons.add("no-external-evidence-collected");
            }

            return VerificationOutcome.of(
                    QualcommVerificationTiers.CONFIGURATION_ONLY,
                    verified,
                    reasons,
                    null,
                    verified
                            ? "NPU execution rests on host observations plus a configured flag;"
                            + " no live inference or benchmark artifact was checked."
                            : "No verification bean is configured and the host plus configuration"
                            + " do not support an NPU execution claim."
            );
        };
    }
}

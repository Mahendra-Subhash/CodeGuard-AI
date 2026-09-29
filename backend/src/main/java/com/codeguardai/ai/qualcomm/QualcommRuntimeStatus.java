package com.codeguardai.ai.qualcomm;

import java.util.List;

/**
 * Factual Qualcomm runtime capability report.
 *
 * Field semantics:
 *
 * - {@code deploymentMode}, {@code modelId}, {@code runtime} and
 *   {@code executionTarget} are CONFIGURED values. They state deployment intent
 *   and are never treated as evidence of execution.
 * - {@code operatingSystem}, {@code osVersion}, {@code cpuArchitecture},
 *   {@code detectedArchitecture} and {@code arm64HostDetected} are detected
 *   host facts.
 * - {@code qnnRuntimeComponentsPresent} is true only when QNN/QAIRT/SNPE
 *   runtime components were actually found on this machine.
 * - {@code configuredExecutionVerification} mirrors the configured flag so the
 *   difference between configuration and evidence stays visible.
 * - {@code npuExecutionVerified} is true only when the evidence rules of
 *   {@link QualcommVerification} accepted the claim. By default that means a live
 *   GenieX server round trip and a benchmark artifact naming the NPU compute unit
 *   both exist and agree on the model that was run.
 * - {@code verificationTier}, {@code verificationReasons},
 *   {@code verificationMessage} and {@code verificationEvidence} expose how that
 *   verdict was reached: the tier reached, one reason code per gate that did not
 *   pass, and everything that was observed.
 * - {@code geniexCliDetected} is an observation about this machine only. In a
 *   remote Qualcomm Device Cloud session the launcher runs on the device, so
 *   absence here neither proves nor disproves execution.
 * - {@code available} is true only when the deployment enabled the runtime and
 *   {@code npuExecutionVerified} is true. Configuration alone never reports the
 *   runtime as available.
 */
public record QualcommRuntimeStatus(
        String deploymentMode,
        String modelId,
        String runtime,
        String executionTarget,
        String operatingSystem,
        String osVersion,
        String cpuArchitecture,
        String detectedArchitecture,
        boolean arm64HostDetected,
        boolean qnnRuntimeComponentsPresent,
        boolean configuredExecutionVerification,
        boolean npuExecutionVerified,
        boolean available,
        List<String> detectedComponentLocations,
        List<String> searchedComponentLocations,
        List<String> detectionNotes,
        String details,
        boolean geniexCliDetected,
        List<String> geniexCliLocations,
        String verificationTier,
        List<String> verificationReasons,
        String verificationMessage,
        VerificationOutcome.Evidence verificationEvidence
) {

    /**
     * Compatibility constructor for callers that report host facts and a
     * configuration verdict without any GenieX evidence gathering. The tier is
     * then {@link QualcommVerificationTiers#CONFIGURATION_ONLY}, which makes the
     * absence of external evidence visible instead of implicit.
     */
    public QualcommRuntimeStatus(
            String deploymentMode,
            String modelId,
            String runtime,
            String executionTarget,
            String operatingSystem,
            String osVersion,
            String cpuArchitecture,
            String detectedArchitecture,
            boolean arm64HostDetected,
            boolean qnnRuntimeComponentsPresent,
            boolean configuredExecutionVerification,
            boolean npuExecutionVerified,
            boolean available,
            List<String> detectedComponentLocations,
            List<String> searchedComponentLocations,
            List<String> detectionNotes,
            String details
    ) {
        this(
                deploymentMode,
                modelId,
                runtime,
                executionTarget,
                operatingSystem,
                osVersion,
                cpuArchitecture,
                detectedArchitecture,
                arm64HostDetected,
                qnnRuntimeComponentsPresent,
                configuredExecutionVerification,
                npuExecutionVerified,
                available,
                detectedComponentLocations,
                searchedComponentLocations,
                detectionNotes,
                details,
                false,
                List.of(),
                QualcommVerificationTiers.CONFIGURATION_ONLY,
                List.of(),
                null,
                null
        );
    }
}

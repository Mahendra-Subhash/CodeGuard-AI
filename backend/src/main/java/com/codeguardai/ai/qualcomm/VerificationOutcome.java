package com.codeguardai.ai.qualcomm;

import java.util.List;
import java.util.Map;

/**
 * Result of the Qualcomm/GenieX verification attempt.
 *
 * <p>The record carries the evidence that was gathered together with the reason
 * codes that explain a negative result, so "not verified" is always traceable to
 * a specific missing or contradicting observation instead of being an unexplained
 * boolean.
 *
 * @param tier                  one of the {@link QualcommVerificationTiers} values
 * @param npuExecutionVerified  true only when the NPU execution claim is supported
 * @param reasons               ordered reason codes; on success they describe the
 *                              gates that were passed, on failure the gates that
 *                              blocked the claim
 * @param evidence              gathered evidence, or {@code null} when nothing was
 *                              observed at all
 * @param message               one sentence factual summary
 */
public record VerificationOutcome(
        String tier,
        boolean npuExecutionVerified,
        List<String> reasons,
        Evidence evidence,
        String message
) {

    public static VerificationOutcome of(
            String tier,
            boolean npuExecutionVerified,
            List<String> reasons,
            Evidence evidence,
            String message
    ) {
        return new VerificationOutcome(
                tier,
                npuExecutionVerified,
                List.copyOf(reasons),
                evidence,
                message
        );
    }

    /**
     * Everything that was observed about the execution of the model.
     *
     * @param geniexCliDetected   true when a {@code geniex} launcher was found on
     *                            this machine. Informational only: in a remote
     *                            Qualcomm Device Cloud session the launcher runs on
     *                            the device, not here, so absence never proves or
     *                            disproves execution
     * @param geniexCliLocations  launcher locations that were found
     * @param serverBaseUrl       the loopback URL that was probed
     * @param serverReachable     true when the GenieX server answered
     * @param modelServed         true when the server served the configured model
     * @param servedModels        model identifiers reported by the server
     * @param inferenceAttempted  true when a chat completion request was sent
     * @param inferenceVerified   true when the server returned non empty content
     * @param inferenceLatencyMs  wall clock latency of that request, when measured
     * @param generatedTokens     tokens reported for that request, when reported
     * @param artifactPresent     true when the benchmark artifact file was readable
     * @param artifactPath        resolved artifact path
     * @param computeUnit         compute unit recorded by the artifact, for example
     *                            {@code npu}, {@code gpu} or {@code cpu}
     * @param plugin              runtime plugin recorded by the artifact, for
     *                            example {@code llama.cpp-qt}
     * @param artifactModel       model identifier recorded by the artifact
     * @param chipset             chipset recorded by the artifact
     * @param artifactHost        device the artifact was captured on
     * @param artifactCapturedAt  capture timestamp of the artifact
     * @param artifactAgeDays     age of the artifact in whole days
     * @param benchStatistics     numeric benchmark statistics copied verbatim from
     *                            the artifact, so the numbers shown are the numbers
     *                            measured rather than reinterpreted ones
     * @param remoteDeviceEvidence true when the evidence describes a remote
     *                            Snapdragon device reached over a tunnel rather
     *                            than this machine
     */
    public record Evidence(
            boolean geniexCliDetected,
            List<String> geniexCliLocations,
            String serverBaseUrl,
            boolean serverReachable,
            boolean modelServed,
            List<String> servedModels,
            boolean inferenceAttempted,
            boolean inferenceVerified,
            Long inferenceLatencyMs,
            Integer generatedTokens,
            boolean artifactPresent,
            String artifactPath,
            String computeUnit,
            String plugin,
            String artifactModel,
            String chipset,
            String artifactHost,
            String artifactCapturedAt,
            Long artifactAgeDays,
            Map<String, Object> benchStatistics,
            boolean remoteDeviceEvidence
    ) {
    }
}

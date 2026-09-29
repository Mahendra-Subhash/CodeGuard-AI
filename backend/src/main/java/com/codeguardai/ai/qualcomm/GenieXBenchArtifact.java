package com.codeguardai.ai.qualcomm;

import java.util.Map;

/**
 * A {@code geniex-bench --output-json} result, read from disk.
 *
 * <p>The GenieX benchmark JSON schema is not published, so this record is
 * populated by tolerant key lookup rather than by bound types: the values below
 * are the first matching key found in the artifact, and every value is optional.
 * Statistics are copied verbatim instead of being reinterpreted, so the numbers
 * shown by the dashboard are the numbers that were measured.
 *
 * @param present     true when the configured artifact file exists and was readable
 * @param parseable   true when the file contained a JSON object
 * @param path        resolved artifact path
 * @param computeUnit compute unit recorded by the artifact, for example
 *                    {@code npu}, {@code gpu} or {@code cpu}
 * @param plugin      runtime plugin recorded by the artifact
 * @param model       model identifier recorded by the artifact
 * @param chipset     chipset recorded by the artifact, when it recorded one
 * @param host        device the artifact was captured on, when it recorded one
 * @param capturedAt  capture timestamp as written in the artifact, when present
 * @param timestampSource {@code artifact} or {@code file-system} depending on which
 *                    timestamp the age was computed from
 * @param ageDays     age of the evidence in whole days
 * @param statistics  numeric statistics copied verbatim from the artifact
 * @param readError   why the artifact could not be used, when it could not
 * @param detail      factual description of what was read
 */
public record GenieXBenchArtifact(
        boolean present,
        boolean parseable,
        String path,
        String computeUnit,
        String plugin,
        String model,
        String chipset,
        String host,
        String capturedAt,
        String timestampSource,
        Long ageDays,
        Map<String, Object> statistics,
        String readError,
        String detail
) {

    public static GenieXBenchArtifact missing(String path, String detail) {
        return new GenieXBenchArtifact(
                false,
                false,
                path,
                null, null, null, null, null, null, null, null,
                Map.of(),
                QualcommVerificationTiers.ARTIFACT_MISSING,
                detail
        );
    }

    public static GenieXBenchArtifact unreadable(
            String path,
            String readError,
            String detail
    ) {
        return new GenieXBenchArtifact(
                true,
                false,
                path,
                null, null, null, null, null, null, null, null,
                Map.of(),
                readError,
                detail
        );
    }
}

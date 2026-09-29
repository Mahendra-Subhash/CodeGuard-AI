package com.codeguardai.ai.qualcomm;

import java.util.List;

/**
 * Verification tiers and reason codes reported by
 * {@link QualcommVerificationService}.
 *
 * <p>Tiers are ordered by strength. Only {@link #NPU_VERIFIED} supports the
 * "Snapdragon/NPU execution verified" claim; every weaker tier is reported with
 * the reason codes that explain why the stronger claim is not made.
 */
public final class QualcommVerificationTiers {

    /** No evidence was gathered; a configured flag alone is used. */
    public static final String CONFIGURATION_ONLY = "configuration-only";
    /** Verification is disabled by configuration or the mode is not recognized. */
    public static final String DISABLED = "disabled";
    /** No GenieX server probe succeeded and no artifact supports the claim. */
    public static final String NO_EVIDENCE = "no-evidence";
    /**
     * The GenieX server answered and served a model, and a chat completion
     * returned content. This proves inference ran, but not on which compute unit,
     * so it never supports the NPU claim.
     */
    public static final String SERVER_REACHABLE = "server-reachable";
    /**
     * A benchmark artifact proves the model ran on the required compute unit, or
     * the artifact and the live server both agree on it.
     */
    public static final String NPU_VERIFIED = "npu-verified";

    /* Reason codes. Parameters are appended after a colon. */
    public static final String MODE_DISABLED = "verification-mode-disabled";
    public static final String MODE_UNKNOWN = "verification-mode-unknown";
    public static final String HOST_NOT_ARM64 = "host-not-arm64";
    public static final String REMOTE_DEVICE_EVIDENCE = "remote-device-evidence";
    public static final String REMOTE_EVIDENCE_UNLABELLED = "remote-evidence-without-device-label";
    public static final String MODE_HTTP_CANNOT_PROVE_COMPUTE_UNIT =
            "mode-http-does-not-prove-compute-unit";
    public static final String SERVER_PROBE_DISABLED = "server-probe-disabled";
    public static final String SERVER_UNREACHABLE = "server-unreachable";
    public static final String MODEL_NOT_SERVED = "model-not-served";
    public static final String INFERENCE_PROBE_DISABLED = "inference-probe-disabled";
    public static final String INFERENCE_EMPTY_RESPONSE = "inference-empty-response";
    public static final String INFERENCE_PROBE_FAILED = "inference-probe-failed";
    public static final String ARTIFACT_MISSING = "artifact-missing";
    public static final String ARTIFACT_UNREADABLE = "artifact-unreadable";
    public static final String ARTIFACT_DEVICE_MISSING = "artifact-device-missing";
    public static final String ARTIFACT_DEVICE_MISMATCH = "artifact-device-mismatch";
    public static final String ARTIFACT_STALE = "artifact-stale";
    public static final String HTTP_ARTIFACT_MISMATCH = "http-artifact-mismatch";
    public static final String ARTIFACT_CHIPSET_MISMATCH = "artifact-chipset-mismatch";
    public static final String GATE_PASSED = "gate-passed";
    public static final String GENIEX_CLI_DETECTED = "geniex-cli-detected";
    public static final String GENIEX_CLI_NOT_DETECTED = "geniex-cli-not-detected";

    private QualcommVerificationTiers() {
    }

    /**
     * @param reason a reason code, optionally followed by a colon and a parameter
     * @return the code without its parameter
     */
    public static String codeOf(String reason) {
        if (reason == null) {
            return "";
        }
        int separator = reason.indexOf(':');
        return separator < 0 ? reason : reason.substring(0, separator);
    }

    public static boolean hasReason(List<String> reasons, String code) {
        for (String reason : reasons) {
            if (code.equals(codeOf(reason))) {
                return true;
            }
        }
        return false;
    }
}

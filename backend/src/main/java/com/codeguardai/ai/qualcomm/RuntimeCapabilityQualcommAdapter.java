package com.codeguardai.ai.qualcomm;

import com.codeguardai.ai.AiAnalysisRequest;
import com.codeguardai.ai.AiAnalysisResponse;
import com.codeguardai.config.QualcommProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Qualcomm adapter that reports runtime capability instead of claiming
 * Snapdragon/NPU execution.
 *
 * Behaviour:
 *
 * <p>Behaviour:
 *
 * - every reported value is either a configured value, a detected host fact, or
 *   a derived flag that requires evidence
 * - {@code npuExecutionVerified} is true only when the injected
 *   {@link QualcommVerification} accepted the claim. The production
 *   implementation, {@link QualcommVerificationService}, requires a live GenieX
 *   server round trip and a {@code geniex-bench} artifact that names the NPU
 *   compute unit and agrees with the server about the model that was run
 * - {@code available} is true only when the runtime is enabled for execution and
 *   {@code npuExecutionVerified} is true, so configuration alone can never make
 *   the runtime available
 * - {@link #analyze(AiAnalysisRequest)} never fabricates a model result: this
 *   adapter does not run models, so it reports the capability facts and returns
 *   the input snippet unchanged with an uncalibrated confidence
 *
 * <p>Deployments that use their own evidence source can supply a different
 * {@link QualcommVerification} or a different {@link QualcommAiAdapter} without
 * changing the application core. Inference itself is served by the existing
 * OpenAI compatible provider stack pointed at a GenieX server; this adapter is
 * the capability report, not the inference path.
 */
@Component
public class RuntimeCapabilityQualcommAdapter implements QualcommAiAdapter {

    public static final String ADAPTER_NAME = "qualcomm-runtime-capability-adapter";

    private final QualcommProperties qualcommProperties;
    private final QualcommRuntimeDetector runtimeDetector;
    private final QualcommVerification verification;

    /**
     * Test friendly constructor that applies the historical rule
     * (host facts plus a configured flag) because no verification bean is
     * supplied.
     */
    public RuntimeCapabilityQualcommAdapter(
            QualcommProperties qualcommProperties,
            QualcommRuntimeDetector runtimeDetector
    ) {
        this(
                qualcommProperties,
                runtimeDetector,
                QualcommVerification.configurationOnly(qualcommProperties)
        );
    }

    /**
     * Production constructor. It is explicitly marked with {@link Autowired}
     * because this class also exposes the two argument constructor above, and
     * Spring needs an explicit hint when more than one constructor is present.
     */
    @Autowired
    public RuntimeCapabilityQualcommAdapter(
            QualcommProperties qualcommProperties,
            QualcommRuntimeDetector runtimeDetector,
            QualcommVerification verification
    ) {
        this.qualcommProperties = qualcommProperties;
        this.runtimeDetector = runtimeDetector;
        this.verification = verification;
    }

    @Override
    public String adapterName() {
        return ADAPTER_NAME;
    }

    @Override
    public boolean isAvailable() {
        return runtimeStatus().available();
    }

    @Override
    public QualcommRuntimeStatus runtimeStatus() {

        QualcommHostFacts facts = runtimeDetector.detect();

        boolean enabled = qualcommProperties.isEnabled();
        boolean configuredVerification = qualcommProperties.isNpuExecutionVerified();

        /*
         * Configuration alone is never evidence of hardware execution: the verdict
         * comes from the verification layer, which requires observed evidence.
         */
        VerificationOutcome outcome = verification.verify(facts);
        boolean npuExecutionVerified = outcome.npuExecutionVerified();

        boolean available = enabled && npuExecutionVerified;

        List<String> notes = new ArrayList<>(facts.detectionNotes());

        notes.add(
                "Configured deployment mode=" + qualcommProperties.getDeploymentMode()
                        + ", runtime=" + qualcommProperties.getRuntime()
                        + ", execution target=" + qualcommProperties.getExecutionTarget()
                        + " (configuration states intent, not execution evidence)."
        );

        if (configuredVerification && !npuExecutionVerified) {
            notes.add(
                    "codeguard.qualcomm.npu-execution-verified=true is configured, but the evidence"
                            + " collected by " + outcome.tier() + " verification does not support it:"
                            + " arm64HostDetected=" + facts.arm64HostDetected()
                            + ", qnnRuntimeComponentsPresent=" + facts.qnnRuntimeComponentsPresent() + "."
            );
        }

        notes.add("Verification tier=" + outcome.tier() + ". " + outcome.message());

        for (String reason : outcome.reasons()) {
            notes.add("Verification reason: " + reason);
        }

        if (!enabled) {
            notes.add("codeguard.qualcomm.enabled=false, so Qualcomm execution is not offered on this deployment.");
        }

        return new QualcommRuntimeStatus(
                qualcommProperties.getDeploymentMode(),
                qualcommProperties.getModelId(),
                qualcommProperties.getRuntime(),
                qualcommProperties.getExecutionTarget(),
                facts.operatingSystem(),
                facts.osVersion(),
                facts.cpuArchitecture(),
                facts.detectedArchitecture(),
                facts.arm64HostDetected(),
                facts.qnnRuntimeComponentsPresent(),
                configuredVerification,
                npuExecutionVerified,
                available,
                facts.detectedComponentLocations(),
                facts.searchedComponentLocations(),
                List.copyOf(notes),
                buildDetails(
                        facts,
                        configuredVerification,
                        enabled,
                        npuExecutionVerified,
                        available,
                        outcome
                ),
                outcome.evidence() != null && outcome.evidence().geniexCliDetected(),
                outcome.evidence() == null
                        ? List.of()
                        : outcome.evidence().geniexCliLocations(),
                outcome.tier(),
                outcome.reasons(),
                outcome.message(),
                outcome.evidence()
        );
    }

    /*
     * The detail text is factual: it states what was detected, separates
     * configured intent from evidence, and never claims acceleration.
     */
    private String buildDetails(
            QualcommHostFacts facts,
            boolean configuredVerification,
            boolean enabled,
            boolean npuExecutionVerified,
            boolean available,
            VerificationOutcome outcome
    ) {

        StringBuilder details = new StringBuilder();

        details.append("Reported facts only. Host: ")
                .append(facts.operatingSystem())
                .append(' ')
                .append(facts.osVersion())
                .append(", cpu architecture ")
                .append(facts.cpuArchitecture())
                .append(" (detected ")
                .append(facts.detectedArchitecture())
                .append("). ");

        details.append(
                facts.qnnRuntimeComponentsPresent()
                        ? "QNN/QAIRT runtime components were detected on this machine. "
                        : "No QNN/QAIRT/SNPE runtime components were detected on this machine. "
        );

        details.append("Configured execution target '")
                .append(qualcommProperties.getExecutionTarget())
                .append("' describes deployment intent only. ");

        if (npuExecutionVerified && available) {
            details.append(
                    "Snapdragon/NPU execution is verified for this deployment and Qualcomm execution is enabled,"
                            + " so the runtime is reported as available. "
            );
        } else if (npuExecutionVerified) {
            details.append(
                    "Snapdragon/NPU execution is verified for this deployment,"
                            + " but Qualcomm execution is disabled by configuration. "
            );
        } else {
            details.append(
                    "Snapdragon/NPU execution is NOT verified on this machine,"
                            + " so no Qualcomm acceleration is claimed. "
            );
        }

        if (configuredVerification && !npuExecutionVerified) {
            details.append(
                    "The configured verification flag is not supported by host evidence and was therefore not accepted. "
            );
        }

        if (!enabled) {
            details.append("Qualcomm execution is disabled by configuration. ");
        }

        details.append("Verification tier is '").append(outcome.tier()).append("'. ");

        details.append("This adapter reports runtime capability only; it does not execute models.");

        return details.toString();
    }

    /*
     * No QNN execution binding exists in this task, so the adapter must not
     * invent an analysis result. It reports the capability status and returns the
     * original code unchanged with an uncalibrated confidence.
     */
    @Override
    public AiAnalysisResponse analyze(AiAnalysisRequest request) {

        QualcommRuntimeStatus status = runtimeStatus();

        String originalCode =
                request.snippet() != null && !request.snippet().isBlank()
                        ? request.snippet()
                        : request.sourceCode();

        String summary = status.npuExecutionVerified()
                ? "Qualcomm runtime capability report: Snapdragon execution is verified for this deployment,"
                        + " but no QNN execution binding is present in this adapter."
                : "Qualcomm runtime capability report: Snapdragon/NPU execution is not verified,"
                        + " so no model was executed.";

        String explanation =
                "This adapter reports runtime capability only and does not execute models. "
                        + status.details();

        String rootCause = status.qnnRuntimeComponentsPresent()
                ? "Qualcomm runtime components are present on this host, but this adapter does not execute models and the accepted evidence does not prove NPU execution: "
                + status.verificationMessage()
                : "No QNN/QAIRT/SNPE runtime components were found on this host, so no Qualcomm execution backend is available: "
                + status.verificationMessage();

        String risk =
                "Reporting model results or acceleration without verified hardware execution would misrepresent the runtime,"
                        + " so no result and no acceleration claim is produced.";

        String recommendedFix =
                "Capture GenieX evidence with scripts/verify-geniex-npu.ps1 (a live 'geniex serve'"
                        + " round trip plus a geniex-bench artifact that records device=npu), point"
                        + " codeguard.qualcomm.server at that server, and re-check GET /api/runtime"
                        + " before claiming NPU acceleration.";

        String verificationHint = status.npuExecutionVerified()
                ? "Snapdragon/NPU execution is verified for this deployment; the accepted evidence is"
                + " reported by GET /api/runtime under the qualcommVerification* keys."
                : "Snapdragon NPU execution is not verified: the run stopped at tier '"
                + status.verificationTier() + "'. The failing gates are listed in"
                + " qualcommVerificationReasons; docs/QUALCOMM-GENIEX.md explains each one.";

        return new AiAnalysisResponse(
                summary,
                explanation,
                rootCause,
                risk,
                recommendedFix,
                originalCode == null ? "" : originalCode,
                "uncalibrated",
                verificationHint
        );
    }
}

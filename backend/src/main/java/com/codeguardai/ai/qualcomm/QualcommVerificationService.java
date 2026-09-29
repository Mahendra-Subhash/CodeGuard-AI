package com.codeguardai.ai.qualcomm;

import com.codeguardai.config.QualcommProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether Snapdragon/NPU execution may be reported as verified.
 *
 * <p>The claim is made only through ordered gates, and every gate that is not
 * passed leaves a reason code behind:
 *
 * <ol>
 *   <li><b>host</b> - the machine is ARM64, or the deployment explicitly allows
 *       evidence about a remote Snapdragon device reached over a tunnel.</li>
 *   <li><b>http-live</b> - a GenieX server answered and serves the configured
 *       model.</li>
 *   <li><b>http-inference</b> - a chat completion against that server returned
 *       content, which proves inference actually ran.</li>
 *   <li><b>artifact-device</b> - a benchmark artifact exists, parses, and records
 *       the required compute unit. An HTTP response never states which unit ran,
 *       so only artifact evidence can support the NPU part of the claim.</li>
 *   <li><b>agreement</b> - the model named by the server and by the artifact are
 *       the same model, and the chipset does not contradict the configuration.
 *       This is what stops a CPU fallback from being presented as NPU
 *       execution.</li>
 *   <li><b>freshness</b> - the artifact is not older than the configured maximum
 *       age, so last quarter's run cannot vouch for this build.</li>
 * </ol>
 *
 * <p>With the default mode {@code both}, gates 2-3 and gates 4-6 must all pass.
 * Mode {@code artifact} drops the live requirement, mode {@code http} drops the
 * artifact requirement and therefore can never support the NPU claim, and mode
 * {@code disabled} never probes and never verifies.
 */
@Service
public class QualcommVerificationService implements QualcommVerification {

    private final QualcommProperties qualcommProperties;
    private final GenieXCliProbe cliProbe;
    private final GenieXServerProbe serverProbe;
    private final GenieXBenchArtifactReader artifactReader;

    public QualcommVerificationService(
            QualcommProperties qualcommProperties,
            GenieXCliProbe cliProbe,
            GenieXServerProbe serverProbe,
            GenieXBenchArtifactReader artifactReader
    ) {
        this.qualcommProperties = qualcommProperties;
        this.cliProbe = cliProbe;
        this.serverProbe = serverProbe;
        this.artifactReader = artifactReader;
    }

    @Override
    public VerificationOutcome verify(QualcommHostFacts facts) {

        QualcommProperties.Verification settings = qualcommProperties.getVerification();
        QualcommProperties.VerificationMode mode =
                QualcommProperties.VerificationMode.from(settings.getMode());

        if (mode == QualcommProperties.VerificationMode.DISABLED) {
            return VerificationOutcome.of(
                    QualcommVerificationTiers.DISABLED,
                    false,
                    List.of(QualcommVerificationTiers.MODE_DISABLED),
                    null,
                    "Qualcomm verification is disabled by configuration"
                            + " (codeguard.qualcomm.verification.mode=disabled),"
                            + " so no evidence was collected and nothing is verified."
            );
        }

        if (mode == QualcommProperties.VerificationMode.UNKNOWN) {
            return VerificationOutcome.of(
                    QualcommVerificationTiers.DISABLED,
                    false,
                    List.of(QualcommVerificationTiers.MODE_UNKNOWN + ":" + settings.getMode()),
                    null,
                    "codeguard.qualcomm.verification.mode='" + settings.getMode()
                            + "' is not a recognized mode; expected both, http, artifact or"
                            + " disabled. No evidence was collected and nothing is verified."
            );
        }

        boolean httpRequired = mode == QualcommProperties.VerificationMode.BOTH
                || mode == QualcommProperties.VerificationMode.HTTP;
        boolean artifactRequired = mode == QualcommProperties.VerificationMode.BOTH
                || mode == QualcommProperties.VerificationMode.ARTIFACT;

        GenieXCliProbe.Result cli = cliProbe.probe();
        GenieXServerProbe.Result http = serverProbe.probe();
        GenieXBenchArtifact artifact = artifactRequired
                ? artifactReader.read()
                : GenieXBenchArtifact.missing(
                settings.getArtifactPath(),
                "The benchmark artifact was not read because verification mode '"
                        + settings.getMode() + "' does not require it."
        );

        List<String> reasons = new ArrayList<>();

        reasons.add(cli.detected()
                ? QualcommVerificationTiers.GENIEX_CLI_DETECTED + ":" + cli.locations()
                : QualcommVerificationTiers.GENIEX_CLI_NOT_DETECTED
                + ":informational-only (the launcher can run on a remote device)");


        /*
         * Gate 1 - host class. An x86_64 build must never report a verified
         * Snapdragon runtime unless the deployment declared that its evidence
         * describes a remote Snapdragon device, and that evidence must name the
         * device it came from.
         */
        boolean hostGate;

        if (facts.arm64HostDetected()) {
            hostGate = true;
            reasons.add(QualcommVerificationTiers.GATE_PASSED + ":host-architecture");
        } else if (settings.isAllowRemoteDevice()) {
            hostGate = true;
            reasons.add(QualcommVerificationTiers.REMOTE_DEVICE_EVIDENCE
                    + ":" + facts.detectedArchitecture());
        } else {
            hostGate = false;
            reasons.add(QualcommVerificationTiers.HOST_NOT_ARM64
                    + ":" + facts.detectedArchitecture());
        }

        boolean remoteEvidenceNeedsLabel =
                !facts.arm64HostDetected() && settings.isAllowRemoteDevice();

        boolean remoteEvidenceLabelled = !remoteEvidenceNeedsLabel
                || !isBlank(artifact.host()) || !isBlank(artifact.chipset());

        if (!remoteEvidenceLabelled) {
            reasons.add(QualcommVerificationTiers.REMOTE_EVIDENCE_UNLABELLED);
        }

        /*
         * Gate 2 + 3 - live inference.
         */
        boolean httpLiveGate = false;
        boolean inferenceGate = false;

        if (httpRequired) {

            if (!http.attempted()) {
                reasons.add(QualcommVerificationTiers.SERVER_PROBE_DISABLED
                        + ":" + http.baseUrl());
            } else if (!http.reachable()) {
                reasons.add(QualcommVerificationTiers.SERVER_UNREACHABLE + ":" + http.baseUrl());
            } else if (!http.modelServed()) {
                reasons.add(QualcommVerificationTiers.MODEL_NOT_SERVED
                        + ":" + (isBlank(http.model()) ? "no-model-configured" : http.model()));
            } else {
                httpLiveGate = true;
                reasons.add(QualcommVerificationTiers.GATE_PASSED + ":http-live");

                if (!settings.isInferenceProbeEnabled()) {
                    reasons.add(QualcommVerificationTiers.INFERENCE_PROBE_DISABLED);
                } else if (!http.inferenceAttempted()) {
                    reasons.add(QualcommVerificationTiers.INFERENCE_PROBE_DISABLED + ":not-run");
                } else if (!http.inferenceVerified()) {
                    reasons.add(QualcommVerificationTiers.INFERENCE_EMPTY_RESPONSE);
                } else {
                    inferenceGate = true;
                    reasons.add(QualcommVerificationTiers.GATE_PASSED + ":http-inference");
                }
            }
        }

        /*
         * Gate 4 - the artifact names the compute unit.
         */
        boolean artifactDeviceGate = false;

        if (artifactRequired) {

            if (!artifact.present()) {
                reasons.add(QualcommVerificationTiers.ARTIFACT_MISSING + ":" + artifact.path());
            } else if (!artifact.parseable()) {
                reasons.add(QualcommVerificationTiers.ARTIFACT_UNREADABLE + ":" + artifact.path());
            } else if (isBlank(artifact.computeUnit())) {
                reasons.add(QualcommVerificationTiers.ARTIFACT_DEVICE_MISSING);
            } else if (!settings.getRequireDevice().isBlank()
                    && !settings.getRequireDevice().trim().equalsIgnoreCase(artifact.computeUnit().trim())) {
                reasons.add(QualcommVerificationTiers.ARTIFACT_DEVICE_MISMATCH
                        + ":" + artifact.computeUnit() + "!=" + settings.getRequireDevice());
            } else {
                artifactDeviceGate = true;
                reasons.add(QualcommVerificationTiers.GATE_PASSED + ":artifact-device");
            }
        }

        /*
         * Gate 5 - agreement. The server and the artifact must describe the same
         * model, and the chipset must not contradict the configuration. Without
         * this gate a CPU fallback that answers quickly would be presented as
         * verified NPU execution.
         */
        boolean agreementGate = false;

        if (artifactRequired && artifactDeviceGate) {

            String disagreement = disagreement(http, artifact, settings);

            if (disagreement != null) {
                reasons.add(disagreement);
            } else {
                agreementGate = true;
                reasons.add(QualcommVerificationTiers.GATE_PASSED + ":agreement");
            }
        }

        /*
         * Gate 6 - freshness.
         */
        boolean freshnessGate = false;

        if (artifactRequired && artifactDeviceGate) {

            int maxAgeDays = settings.getMaxArtifactAgeDays();

            if (maxAgeDays <= 0) {
                freshnessGate = true;
                reasons.add(QualcommVerificationTiers.GATE_PASSED + ":freshness-unbounded");
            } else if (artifact.ageDays() == null) {
                reasons.add(QualcommVerificationTiers.ARTIFACT_STALE + ":unknown-timestamp");
            } else if (artifact.ageDays() > maxAgeDays) {
                reasons.add(QualcommVerificationTiers.ARTIFACT_STALE
                        + ":" + artifact.ageDays() + "d>max" + maxAgeDays + "d");
            } else {
                freshnessGate = true;
                reasons.add(QualcommVerificationTiers.GATE_PASSED + ":"
                        + "freshness-" + artifact.ageDays() + "d<max" + maxAgeDays + "d");
            }
        }

        if (mode == QualcommProperties.VerificationMode.HTTP) {
            reasons.add(QualcommVerificationTiers.MODE_HTTP_CANNOT_PROVE_COMPUTE_UNIT);
        }

        /*
         * Only artifact evidence can name the compute unit, so the NPU claim
         * always needs gate 4-6 in addition to whatever the mode requires.
         */
        boolean npuExecutionVerified = hostGate
                && remoteEvidenceLabelled
                && artifactRequired
                && artifactDeviceGate
                && agreementGate
                && freshnessGate
                && (mode == QualcommProperties.VerificationMode.ARTIFACT
                || (httpLiveGate && inferenceGate));

        String tier;

        if (npuExecutionVerified) {
            tier = QualcommVerificationTiers.NPU_VERIFIED;
        } else if (httpLiveGate && inferenceGate) {
            tier = QualcommVerificationTiers.SERVER_REACHABLE;
        } else {
            tier = QualcommVerificationTiers.NO_EVIDENCE;
        }

        VerificationOutcome.Evidence evidence = new VerificationOutcome.Evidence(
                cli.detected(),
                cli.locations(),
                http.baseUrl(),
                http.reachable(),
                http.modelServed(),
                http.servedModels(),
                http.inferenceAttempted(),
                http.inferenceVerified(),
                http.inferenceLatencyMs(),
                http.generatedTokens(),
                artifact.present(),
                artifact.path(),
                artifact.computeUnit(),
                artifact.plugin(),
                artifact.model(),
                artifact.chipset(),
                artifact.host(),
                artifact.capturedAt(),
                artifact.ageDays(),
                artifact.statistics(),
                remoteEvidenceNeedsLabel
        );

        String message;

        if (npuExecutionVerified) {
            message = "Snapdragon/NPU execution is verified: the GenieX server served '"
                    + http.model() + "' and ran a completion, and the benchmark artifact at "
                    + artifact.path() + " records device=" + artifact.computeUnit()
                    + (isBlank(artifact.chipset()) ? "" : " on " + artifact.chipset())
                    + ".";
        } else if (tier.equals(QualcommVerificationTiers.SERVER_REACHABLE)) {
            message = "A GenieX server served the model and a completion returned content,"
                    + " but the compute unit that executed it is not proven by an artifact,"
                    + " so NPU execution is not claimed. " + http.detail();
        } else {
            message = "No accepted evidence supports a Snapdragon/NPU execution claim."
                    + " " + http.detail() + " " + artifact.detail();
        }

        return VerificationOutcome.of(tier, npuExecutionVerified, reasons, evidence, message);
    }

    /**
     * @return a reason code when the artifact and the live server (or the
     *         configured chipset label) describe different things, otherwise
     *         {@code null}. Evidence that does not name a model or chipset is not
     *         treated as a contradiction; it simply cannot confirm one either.
     */
    private String disagreement(
            GenieXServerProbe.Result http,
            GenieXBenchArtifact artifact,
            QualcommProperties.Verification settings
    ) {

        String configuredChipset = settings.getChipsetLabel();
        String artifactChipset = artifact.chipset();

        if (!isBlank(configuredChipset)
                && !isBlank(artifactChipset)
                && !normalizeLabel(configuredChipset).equals(normalizeLabel(artifactChipset))) {
            return QualcommVerificationTiers.ARTIFACT_CHIPSET_MISMATCH
                    + ":" + artifactChipset + "!=" + configuredChipset;
        }

        String serverModel = http.modelServed() ? http.model() : null;

        if (!isBlank(serverModel)
                && !isBlank(artifact.model())
                && !sameModel(serverModel, artifact.model())) {
            return QualcommVerificationTiers.HTTP_ARTIFACT_MISMATCH
                    + ":" + artifact.model() + "!=" + serverModel;
        }

        return null;
    }

    /**
     * A benchmark run often records the local cache path of a model
     * ({@code ...\org\repo\8b-precision-...}) while the server reports the
     * identifier ({@code org/repo:precision}), so the comparison is deliberately
     * forgiving. It only rejects identifiers that clearly differ, because the
     * purpose of this gate is to catch a different model (for example a CPU
     * fallback build), not to compare spelling.
     */
    private boolean sameModel(String serverModel, String artifactModel) {

        String left = normalizeModel(serverModel);
        String right = normalizeModel(artifactModel);

        return left.equals(right) || left.contains(right) || right.contains(left);
    }

    private String normalizeModel(String value) {

        String normalized = normalizeLabel(value).replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        int separator = normalized.indexOf(':', lastSlash + 1);

        if (separator > 0) {
            normalized = normalized.substring(0, separator);
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private String normalizeLabel(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}



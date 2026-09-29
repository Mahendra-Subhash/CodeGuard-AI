package com.codeguardai.ai.qualcomm;

import com.codeguardai.config.QualcommProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the rules behind {@code qualcommNpuVerified}.
 *
 * <p>The important cases are the ones that must be refused: a configured flag
 * with no evidence, a benchmark run that recorded a CPU fallback, an artifact
 * that names a different model than the one the server served, evidence that is
 * too old to describe this build, and any of those on a machine that is not an
 * ARM64 Snapdragon host.
 */
class QualcommVerificationServiceTest {

    private static final String MODEL = "qualcommiq/Llama-3.1-8B-Instruct";

    @TempDir
    Path directory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void aConfiguredFlagWithoutEvidenceNeverVerifies() {

        QualcommProperties properties = properties("both");
        properties.setNpuExecutionVerified(true);

        VerificationOutcome outcome = service(properties).verify(arm64Facts());

        assertThat(outcome.npuExecutionVerified()).isFalse();
        assertThat(outcome.tier()).isEqualTo(QualcommVerificationTiers.NO_EVIDENCE);
        assertThat(QualcommVerificationTiers.hasReason(
                outcome.reasons(), QualcommVerificationTiers.SERVER_PROBE_DISABLED)).isTrue();
        assertThat(QualcommVerificationTiers.hasReason(
                outcome.reasons(), QualcommVerificationTiers.ARTIFACT_MISSING)).isTrue();
    }

    @Test
    void aLiveServerRoundTripAndAnNpuArtifactTogetherVerify() throws Exception {

        startServing();

        QualcommProperties properties = liveProperties("both");
        properties.getVerification().setArtifactPath(
                artifact("npu", "C:\\geniex\\cache\\" + MODEL + "\\8b-precision", Instant.now()));

        VerificationOutcome outcome = service(properties).verify(arm64Facts());

        assertThat(outcome.npuExecutionVerified()).isTrue();
        assertThat(outcome.tier()).isEqualTo(QualcommVerificationTiers.NPU_VERIFIED);
        assertThat(outcome.evidence().inferenceVerified()).isTrue();
        assertThat(outcome.evidence().computeUnit()).isEqualTo("npu");
        assertThat(outcome.evidence().artifactModel()).contains(MODEL);
        assertThat(outcome.message()).contains("verified");
    }

    @Test
    void anArtifactThatRecordedACpuFallbackBlocksTheClaim() throws Exception {

        startServing();

        QualcommProperties properties = liveProperties("both");
        properties.getVerification().setArtifactPath(artifact("cpu", MODEL, Instant.now()));

        VerificationOutcome outcome = service(properties).verify(arm64Facts());

        assertThat(outcome.npuExecutionVerified()).isFalse();
        assertThat(QualcommVerificationTiers.hasReason(
                outcome.reasons(), QualcommVerificationTiers.ARTIFACT_DEVICE_MISMATCH)).isTrue();
        assertThat(outcome.evidence().computeUnit()).isEqualTo("cpu");
    }

    @Test
    void anArtifactAboutADifferentModelBlocksTheClaim() throws Exception {

        startServing();

        QualcommProperties properties = liveProperties("both");
        properties.getVerification().setArtifactPath(
                artifact("npu", "qualcommiq/Smaller-Model", Instant.now()));

        VerificationOutcome outcome = service(properties).verify(arm64Facts());

        assertThat(outcome.npuExecutionVerified()).isFalse();
        assertThat(QualcommVerificationTiers.hasReason(
                outcome.reasons(), QualcommVerificationTiers.HTTP_ARTIFACT_MISMATCH)).isTrue();
    }


    @Test
    void evidenceOlderThanTheConfiguredMaximumIsRejected() throws Exception {

        startServing();

        QualcommProperties properties = liveProperties("both");
        properties.getVerification().setArtifactPath(
                artifact("npu", MODEL, Instant.now().minus(400, ChronoUnit.DAYS)));

        VerificationOutcome outcome = service(properties).verify(arm64Facts());

        assertThat(outcome.npuExecutionVerified()).isFalse();
        assertThat(QualcommVerificationTiers.hasReason(
                outcome.reasons(), QualcommVerificationTiers.ARTIFACT_STALE)).isTrue();
    }

    @Test
    void anX86HostRejectsEvenCompleteEvidence() throws Exception {

        startServing();

        QualcommProperties properties = liveProperties("both");
        properties.getVerification().setArtifactPath(artifact("npu", MODEL, Instant.now()));

        VerificationOutcome outcome = service(properties).verify(x86Facts());

        assertThat(outcome.npuExecutionVerified()).isFalse();
        assertThat(QualcommVerificationTiers.hasReason(
                outcome.reasons(), QualcommVerificationTiers.HOST_NOT_ARM64)).isTrue();
    }

    @Test
    void remoteEvidenceIsAcceptedOnlyWhenTheArtifactNamesTheDevice() throws Exception {

        startServing();

        QualcommProperties labelled = liveProperties("both");
        labelled.getVerification().setAllowRemoteDevice(true);
        labelled.getVerification().setArtifactPath(artifact("npu", MODEL, Instant.now()));

        VerificationOutcome labelledOutcome = service(labelled).verify(x86Facts());

        assertThat(labelledOutcome.npuExecutionVerified()).isTrue();
        assertThat(labelledOutcome.evidence().remoteDeviceEvidence()).isTrue();

        QualcommProperties unlabelled = liveProperties("both");
        unlabelled.getVerification().setAllowRemoteDevice(true);
        unlabelled.getVerification().setArtifactPath(
                artifactWithoutDevice("npu", MODEL, Instant.now()));

        VerificationOutcome unlabelledOutcome = service(unlabelled).verify(x86Facts());

        assertThat(unlabelledOutcome.npuExecutionVerified()).isFalse();
        assertThat(QualcommVerificationTiers.hasReason(
                unlabelledOutcome.reasons(),
                QualcommVerificationTiers.REMOTE_EVIDENCE_UNLABELLED)).isTrue();
    }

    @Test
    void httpModeNeverClaimsTheNpuEvenWhenTheServerAnswers() throws Exception {

        startServing();

        QualcommProperties properties = liveProperties("http");

        VerificationOutcome outcome = service(properties).verify(arm64Facts());

        assertThat(outcome.tier()).isEqualTo(QualcommVerificationTiers.SERVER_REACHABLE);
        assertThat(outcome.npuExecutionVerified()).isFalse();
        assertThat(QualcommVerificationTiers.hasReason(
                outcome.reasons(), QualcommVerificationTiers.MODE_HTTP_CANNOT_PROVE_COMPUTE_UNIT))
                .isTrue();
    }

    @Test
    void disabledModeCollectsNoEvidenceAtAll() {

        QualcommProperties properties = properties("disabled");

        VerificationOutcome outcome = service(properties).verify(arm64Facts());

        assertThat(outcome.tier()).isEqualTo(QualcommVerificationTiers.DISABLED);
        assertThat(outcome.npuExecutionVerified()).isFalse();
        assertThat(outcome.evidence()).isNull();
        assertThat(outcome.reasons())
                .containsExactly(QualcommVerificationTiers.MODE_DISABLED);
    }

    private int artifactIndex;

    private QualcommProperties properties(String mode) {

        QualcommProperties properties = new QualcommProperties();
        properties.getVerification().setMode(mode);
        properties.getVerification().setArtifactPath(
                directory.resolve("no-artifact-here.json").toString());

        return properties;
    }

    /**
     * Properties pointed at a local GenieX stand-in. The probe must be enabled
     * explicitly, which is also the behaviour a real deployment has to opt into.
     */
    private QualcommProperties liveProperties(String mode) throws IOException {

        if (server == null) {
            startServing();
        }

        QualcommProperties properties = properties(mode);
        properties.getServer().setEnabled(true);
        properties.getServer().setBaseUrl(baseUrl());
        properties.getServer().setModel(MODEL);
        properties.getServer().setConnectTimeoutMs(1000);
        properties.getServer().setReadTimeoutMs(3000);

        return properties;
    }

    private QualcommVerificationService service(QualcommProperties properties) {

        return new QualcommVerificationService(
                properties,
                new GenieXCliProbe(properties),
                new GenieXServerProbe(properties, new ObjectMapper()),
                new GenieXBenchArtifactReader(properties, new ObjectMapper())
        );
    }

    private String artifact(String device, String model, Instant capturedAt) throws IOException {

        return writeArtifact(device, model, capturedAt, "Snapdragon X Elite", "sqd-x-elite-01");
    }

    private String artifactWithoutDevice(String device, String model, Instant capturedAt)
            throws IOException {

        return writeArtifact(device, model, capturedAt, "", "");
    }

    private String writeArtifact(
            String device,
            String model,
            Instant capturedAt,
            String chipset,
            String host
    ) throws IOException {

        Path path = directory.resolve("geniex-bench-" + (artifactIndex++) + ".json");

        Files.writeString(
                path,
                """
                        {
                          "device": "%s",
                          "plugin": "llama.cpp-qt",
                          "model": "%s",
                          "chipset": "%s",
                          "host": "%s",
                          "captured_at": "%s",
                          "stats": { "tokens_per_second": 27.4 }
                        }
                        """.formatted(
                        json(device),
                        json(model),
                        json(chipset),
                        json(host),
                        capturedAt
                ),
                StandardCharsets.UTF_8
        );

        return path.toString();
    }

    /*
     * Benchmark runs record Windows cache paths, so the fixture has to emit them
     * as valid JSON: an unescaped backslash is not a legal JSON escape.
     */
    private static String json(String value) {

        if (value == null) {
            return "";
        }

        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void startServing() throws IOException {

        String models = "{\"object\":\"list\",\"data\":[{\"id\":\"" + MODEL + "\"}]}";
        String completion =
                "{\"choices\":[{\"message\":{\"content\":\"Pong\"}}],"
                        + "\"usage\":{\"completion_tokens\":2}}";

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> respond(exchange, "{\"status\":\"ok\"}"));
        server.createContext("/v1/models", exchange -> respond(exchange, models));
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, completion));
        server.start();
    }

    private void respond(HttpExchange exchange, String body) throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static QualcommHostFacts arm64Facts() {
        return facts("aarch64", QualcommHostFacts.ARCHITECTURE_ARM64, true);
    }

    private static QualcommHostFacts x86Facts() {
        return facts("amd64", QualcommHostFacts.ARCHITECTURE_X86_64, false);
    }

    private static QualcommHostFacts facts(String arch, String normalized, boolean arm64) {

        return new QualcommHostFacts(
                "Windows 11",
                "10.0",
                arch,
                normalized,
                arm64,
                true,
                List.of(),
                List.of("environment QNN_SDK_ROOT"),
                List.of("test observations")
        );
    }
}


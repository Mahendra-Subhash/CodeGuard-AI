package com.codeguardai.ai.qualcomm;

import com.codeguardai.config.QualcommProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies what the GenieX server probe may and may not claim.
 *
 * <p>The probe performs real HTTP calls, so these tests run it against a local
 * loopback HTTP server that answers with the shapes a {@code geniex serve}
 * process produces. Nothing here leaves the loopback interface.
 */
class GenieXServerProbeTest {

    private static final String MODEL =
            "qualcommiq/Llama-3.1-8B-Instruct:8b-precision-rr-20250504";

    private static final String MODELS_BODY =
            "{\"object\":\"list\",\"data\":[{\"id\":\"" + MODEL + "\",\"object\":\"model\"}]}";

    private static final String CHAT_BODY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Pong\"}}],"
                    + "\"usage\":{\"completion_tokens\":3}}";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void doesNotProbeAtAllWhenTheServerIsDisabled() {

        QualcommProperties properties = new QualcommProperties();

        GenieXServerProbe.Result result =
                new GenieXServerProbe(properties, new ObjectMapper()).probe();

        assertThat(result.attempted()).isFalse();
        assertThat(result.reachable()).isFalse();
        assertThat(result.inferenceAttempted()).isFalse();
        assertThat(result.detail()).contains("codeguard.qualcomm.server.enabled=false");
    }

    @Test
    void reportsUnreachableWhenNothingAnswers() {

        QualcommProperties properties = properties("http://127.0.0.1:1", MODEL);

        GenieXServerProbe.Result result =
                new GenieXServerProbe(properties, new ObjectMapper()).probe();

        assertThat(result.attempted()).isTrue();
        assertThat(result.reachable()).isFalse();
        assertThat(result.inferenceAttempted()).isFalse();
        assertThat(result.detail()).contains("No GenieX server answered");
    }

    @Test
    void reportsInferenceVerifiedOnlyAfterACompletionReturnedContent() throws Exception {

        startServing(MODELS_BODY, CHAT_BODY, 200);

        QualcommProperties properties = properties(baseUrl(), MODEL);

        GenieXServerProbe.Result result =
                new GenieXServerProbe(properties, new ObjectMapper()).probe();

        assertThat(result.reachable()).isTrue();
        assertThat(result.healthOk()).isTrue();
        assertThat(result.servedModels()).contains(MODEL);
        assertThat(result.modelServed()).isTrue();
        assertThat(result.inferenceAttempted()).isTrue();
        assertThat(result.inferenceVerified()).isTrue();
        assertThat(result.generatedTokens()).isEqualTo(3);
        assertThat(result.inferenceLatencyMs()).isNotNull();
        assertThat(result.detail()).contains("returned content");
    }

    @Test
    void acceptsTheConfiguredModelWithoutItsPrecisionSuffix() throws Exception {

        startServing(MODELS_BODY, CHAT_BODY, 200);

        QualcommProperties properties =
                properties(baseUrl(), "qualcommiq/Llama-3.1-8B-Instruct");

        GenieXServerProbe.Result result =
                new GenieXServerProbe(properties, new ObjectMapper()).probe();

        assertThat(result.modelServed()).isTrue();
    }

    @Test
    void doesNotRunInferenceWhenTheConfiguredModelIsNotServed() throws Exception {

        startServing(MODELS_BODY, CHAT_BODY, 200);

        QualcommProperties properties =
                properties(baseUrl(), "qualcommiq/Some-Other-Model");

        GenieXServerProbe.Result result =
                new GenieXServerProbe(properties, new ObjectMapper()).probe();

        assertThat(result.reachable()).isTrue();
        assertThat(result.modelServed()).isFalse();
        assertThat(result.inferenceAttempted()).isFalse();
        assertThat(result.detail()).contains("is not serving");
    }

    @Test
    void treatsEmptyCompletionContentAsUnverified() throws Exception {

        startServing(MODELS_BODY, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}", 200);

        QualcommProperties properties = properties(baseUrl(), MODEL);

        GenieXServerProbe.Result result =
                new GenieXServerProbe(properties, new ObjectMapper()).probe();

        assertThat(result.inferenceAttempted()).isTrue();
        assertThat(result.inferenceVerified()).isFalse();
        assertThat(result.detail()).contains("empty content");
    }

    @Test
    void reportsAFailedCompletionWithoutClaimingInference() throws Exception {

        startServing(MODELS_BODY, "{\"error\":\"model failed to load\"}", 500);

        QualcommProperties properties = properties(baseUrl(), MODEL);

        GenieXServerProbe.Result result =
                new GenieXServerProbe(properties, new ObjectMapper()).probe();

        assertThat(result.inferenceAttempted()).isTrue();
        assertThat(result.inferenceVerified()).isFalse();
        assertThat(result.detail()).contains("chat completion request");
    }

    @Test
    void skipsTheCompletionRoundTripWhenTheProbeIsDisabled() throws Exception {

        startServing(MODELS_BODY, CHAT_BODY, 200);

        QualcommProperties properties = properties(baseUrl(), MODEL);
        properties.getVerification().setInferenceProbeEnabled(false);

        GenieXServerProbe.Result result =
                new GenieXServerProbe(properties, new ObjectMapper()).probe();

        assertThat(result.modelServed()).isTrue();
        assertThat(result.inferenceAttempted()).isFalse();
        assertThat(result.detail()).contains("inference-probe-enabled=false");
    }

    @Test
    void refusesToTargetANonLoopbackGeniexHost() {

        QualcommProperties properties = new QualcommProperties();

        assertThrows(
                SecurityException.class,
                () -> properties.getServer().setBaseUrl("https://geniex.example.com:18181")
        );
    }

    private QualcommProperties properties(String baseUrl, String model) {

        QualcommProperties properties = new QualcommProperties();
        properties.getServer().setEnabled(true);
        properties.getServer().setBaseUrl(baseUrl);
        properties.getServer().setModel(model);
        properties.getServer().setConnectTimeoutMs(1000);
        properties.getServer().setReadTimeoutMs(3000);

        return properties;
    }

    private void startServing(String modelsBody, String chatBody, int chatStatus)
            throws IOException {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));

        server.createContext("/v1/models", exchange -> respond(exchange, 200, modelsBody));

        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(exchange, chatStatus, chatBody)
        );

        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}


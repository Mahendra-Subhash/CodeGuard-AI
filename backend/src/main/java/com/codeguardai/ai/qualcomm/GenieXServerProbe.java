package com.codeguardai.ai.qualcomm;

import com.codeguardai.config.AiProperties;
import com.codeguardai.config.QualcommProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Probes a GenieX local server over its OpenAI compatible HTTP API.
 *
 * <p>The GenieX server ({@code geniex serve ... --connection-mode local}) listens
 * on {@code http://127.0.0.1:18181} and has no authentication. CodeGuard only
 * ever talks to a loopback address: {@link AiProperties#validateLoopbackUrl(String)}
 * is applied before any request, both here and in
 * {@link QualcommProperties.Server#setBaseUrl(String)}, so the backend cannot be
 * pointed at a raw remote Qualcomm Device Cloud endpoint. A remote device is
 * reached by forwarding its loopback port over SSH (see
 * {@code scripts/start-geniex-tunnel.ps1}), which keeps this invariant true.
 *
 * <p>Two distinct facts are collected and never conflated:
 * <ul>
 *   <li>the server answered and served a model - proof that inference is
 *       reachable,</li>
 *   <li>a chat completion produced content - proof that inference actually ran.</li>
 * </ul>
 * The compute unit that executed the model is not reported by the API, which is
 * why {@link GenieXBenchArtifactReader} evidence is required for the NPU claim.
 */
@Component
public class GenieXServerProbe {

    private static final Logger log = LoggerFactory.getLogger(GenieXServerProbe.class);

    /** The probe runs inside a REST call, so it must stay fast. */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 1500;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5000;
    private static final int MAX_INFERENCE_TOKENS = 16;
    private static final String PROBE_PROMPT =
            "Reply with exactly one word and nothing else.";

    private final QualcommProperties qualcommProperties;
    private final ObjectMapper objectMapper;

    public GenieXServerProbe(
            QualcommProperties qualcommProperties,
            ObjectMapper objectMapper
    ) {
        this.qualcommProperties = qualcommProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * @param attempted          false when the probe is disabled by configuration
     * @param reachable          true when the server answered any request
     * @param healthOk           true when {@code GET /health} answered with 2xx
     * @param modelServed        true when the configured model is served
     * @param servedModels       identifiers reported by {@code GET /v1/models}
     * @param inferenceAttempted true when a chat completion request was sent
     * @param inferenceVerified  true when that request returned non empty content
     * @param inferenceLatencyMs wall clock latency of the inference request
     * @param generatedTokens    tokens reported by the server, when it reported any
     * @param baseUrl            the loopback URL that was probed
     * @param model              the configured model identifier, when configured
     * @param detail             factual description of the outcome
     */
    public record Result(
            boolean attempted,
            boolean reachable,
            boolean healthOk,
            boolean modelServed,
            List<String> servedModels,
            boolean inferenceAttempted,
            boolean inferenceVerified,
            Long inferenceLatencyMs,
            Integer generatedTokens,
            String baseUrl,
            String model,
            String detail
    ) {

        static Result notAttempted(String baseUrl, String reason) {
            return new Result(
                    false, false, false, false, List.of(),
                    false, false, null, null, baseUrl, "",
                    "GenieX server probe was not attempted: " + reason
            );
        }
    }

    public Result probe() {

        QualcommProperties.Server server = qualcommProperties.getServer();
        String configuredModel = nullToEmpty(server.getModel()).trim();

        if (!server.isEnabled()) {
            return Result.notAttempted(
                    server.getBaseUrl(),
                    "codeguard.qualcomm.server.enabled=false"
            );
        }

        /*
         * Rejected here rather than deep inside the HTTP stack: a non loopback URL
         * must never be contacted by this process.
         */
        AiProperties.validateLoopbackUrl(server.getBaseUrl());

        RestClient client = buildClient(server);

        boolean healthOk = false;

        try {
            healthOk = client.get()
                    .uri("/health")
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode()
                    .is2xxSuccessful();
        } catch (Exception exception) {
            log.debug("GenieX /health probe failed: {}", exception.getMessage());
        }

        boolean reached = healthOk;
        List<String> servedModels = List.of();

        try {
            String body = client.get()
                    .uri("/v1/models")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);

            servedModels = parseModelIds(body);
            reached = true;
        } catch (Exception exception) {
            log.debug("GenieX /v1/models probe failed: {}", exception.getMessage());
        }

        boolean modelServed = !configuredModel.isEmpty()
                && servesModel(servedModels, configuredModel);

        boolean inferenceAttempted = false;
        boolean inferenceVerified = false;
        Long latencyMs = null;
        Integer generatedTokens = null;
        String detail;

        if (!reached) {
            detail = "No GenieX server answered at " + server.getBaseUrl()
                    + ". Start one with 'geniex serve <model> --connection-mode local'"
                    + " or forward a remote device with scripts/start-geniex-tunnel.ps1.";
        } else if (configuredModel.isEmpty()) {
            detail = "A GenieX server answered at " + server.getBaseUrl()
                    + " and reported " + servedModels.size() + " model(s), but no model"
                    + " identifier is configured, so the served model could not be confirmed.";
        } else if (!modelServed) {
            detail = "A GenieX server answered at " + server.getBaseUrl()
                    + " but is not serving '" + configuredModel + "'. Served: "
                    + String.join(", ", servedModels);
        } else if (!qualcommProperties.getVerification().isInferenceProbeEnabled()) {
            detail = "The GenieX server serves '" + configuredModel + "', but"
                    + " codeguard.qualcomm.verification.inference-probe-enabled=false,"
                    + " so no inference round trip was performed.";
        } else {
            inferenceAttempted = true;
            long startedAt = System.nanoTime();

            try {
                String body = client.post()
                        .uri("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "model", configuredModel,
                                "messages", List.of(Map.of(
                                        "role", "user",
                                        "content", PROBE_PROMPT
                                )),
                                "max_tokens", MAX_INFERENCE_TOKENS,
                                "temperature", 0
                        ))
                        .retrieve()
                        .body(String.class);

                latencyMs = elapsedMillis(startedAt);
                JsonNode root = objectMapper.readTree(nullToEmpty(body));
                String content = textAt(root, "choices", 0, "message", "content");

                inferenceVerified = content != null && !content.isBlank();
                Integer reported = intAt(root, "usage", "completion_tokens");
                generatedTokens = reported != null
                        ? reported
                        : intAt(root, "stats", "completion_tokens");

                detail = inferenceVerified
                        ? "The GenieX server served '" + configuredModel
                        + "' and a chat completion returned content in " + latencyMs + " ms."
                        : "The GenieX server answered the chat completion request for '"
                        + configuredModel + "' with empty content.";
            } catch (Exception exception) {
                latencyMs = elapsedMillis(startedAt);
                detail = "The GenieX chat completion request for '" + configuredModel
                        + "' failed: " + rootMessage(exception);
                log.debug("GenieX inference probe failed: {}", exception.getMessage());
            }
        }

        return new Result(
                true,
                reached,
                healthOk,
                modelServed,
                servedModels,
                inferenceAttempted,
                inferenceVerified,
                latencyMs,
                generatedTokens,
                server.getBaseUrl(),
                configuredModel,
                detail
        );
    }

    private RestClient buildClient(QualcommProperties.Server server) {

        int connectTimeout = server.getConnectTimeoutMs() > 0
                ? server.getConnectTimeoutMs()
                : DEFAULT_CONNECT_TIMEOUT_MS;
        int readTimeout = server.getReadTimeoutMs() > 0
                ? server.getReadTimeoutMs()
                : DEFAULT_READ_TIMEOUT_MS;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeout));
        factory.setReadTimeout(Duration.ofMillis(readTimeout));

        return RestClient.builder()
                .baseUrl(server.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * GenieX identifiers can carry a precision suffix, for example
     * {@code org/repo:8b-precision-rr-20250504}. The served list is compared with
     * and without that suffix so an intentionally partial configuration still
     * matches exactly one reported identifier.
     */
    private boolean servesModel(List<String> servedModels, String configuredModel) {

        for (String served : servedModels) {
            if (served.equalsIgnoreCase(configuredModel)) {
                return true;
            }

            int separator = served.indexOf(':');

            if (separator > 0
                    && served.substring(0, separator).equalsIgnoreCase(configuredModel)) {
                return true;
            }
        }

        return false;
    }

    private List<String> parseModelIds(String body) {

        List<String> ids = new ArrayList<>();

        if (body == null || body.isBlank()) {
            return ids;
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.has("data") ? root.get("data") : root.get("models");

            if (data == null || !data.isArray()) {
                return ids;
            }

            for (JsonNode entry : data) {
                String id = entry.hasNonNull("id")
                        ? entry.get("id").asText()
                        : entry.path("model_id").asText(entry.path("name").asText(""));

                if (!id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        } catch (Exception exception) {
            log.debug("Unable to parse the GenieX model list: {}", exception.getMessage());
        }

        return ids;
    }

    private String textAt(JsonNode root, Object... path) {

        JsonNode current = root;

        for (Object segment : path) {
            if (current == null) {
                return null;
            }
            current = segment instanceof Integer index
                    ? current.get(index)
                    : current.get((String) segment);
        }

        return current == null || current.isNull() ? null : current.asText();
    }

    private Integer intAt(JsonNode root, String... path) {

        JsonNode node = root;

        for (String segment : path) {
            if (node == null) {
                return null;
            }
            node = node.get(segment);
        }

        return node == null || !node.isNumber() ? null : node.asInt();
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String rootMessage(Throwable throwable) {

        Throwable current = throwable;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        String message = current.getMessage();

        return current.getClass().getSimpleName()
                + (message == null || message.isBlank()
                ? ""
                : ": " + message.toLowerCase(Locale.ROOT));
    }
}


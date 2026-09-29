package com.codeguardai.ai;

import com.codeguardai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Probes the configured local inference provider so the runtime report can
 * distinguish "configured" from "actually responding".
 *
 * <p>This performs a real request to the loopback llama-server
 * {@code GET /v1/models} endpoint. It never reports availability from
 * configuration alone, and it never contacts a non-loopback host because
 * {@link AiProperties#validateLoopbackUrl(String)} is applied first.
 */
@Component
public class AiProviderHealthProbe {

    private static final Logger log =
            LoggerFactory.getLogger(AiProviderHealthProbe.class);

    /*
     * The probe must stay fast: it runs inside a REST endpoint call, so it
     * cannot use the full inference timeout.
     */
    private static final int PROBE_CONNECT_TIMEOUT_MS = 1500;
    private static final int PROBE_READ_TIMEOUT_MS = 3000;

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public AiProviderHealthProbe(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    public HealthStatus probe() {

        if (!aiProperties.isHealthCheckEnabled()) {
            return new HealthStatus(
                    false,
                    false,
                    "skipped",
                    List.of(),
                    "Provider health checking is disabled (codeguard.ai.health-check-enabled=false)."
            );
        }

        AiProperties.validateLoopbackUrl(aiProperties.getBaseUrl());

        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(PROBE_CONNECT_TIMEOUT_MS));
        factory.setReadTimeout(Duration.ofMillis(PROBE_READ_TIMEOUT_MS));

        RestClient client =
                RestClient.builder()
                        .baseUrl(aiProperties.getBaseUrl())
                        .requestFactory(factory)
                        .build();

        try {
            String body =
                    client.get()
                            .uri("/v1/models")
                            .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                            .retrieve()
                            .body(String.class);

            List<String> servedModels =
                    parseModelIds(body);

            boolean modelServed =
                    aiProperties.getModel() != null
                            && servedModels.contains(aiProperties.getModel());

            return new HealthStatus(
                    true,
                    modelServed,
                    "responding",
                    servedModels,
                    modelServed
                            ? "Local provider is responding and serving the configured model."
                            : "Local provider is responding, but it is not serving the configured model"
                            + " '" + aiProperties.getModel() + "'."
                            + " Served: " + String.join(", ", servedModels)
            );

        } catch (Exception exception) {
            log.debug("Local AI provider probe failed: {}", exception.getMessage());

            return new HealthStatus(
                    false,
                    false,
                    "unreachable",
                    List.of(),
                    "Local provider is not reachable at " + aiProperties.getBaseUrl()
                            + ": " + rootMessage(exception)
            );
        }
    }

    private List<String> parseModelIds(String body) {

        List<String> ids = new ArrayList<>();

        if (body == null || body.isBlank()) {
            return ids;
        }

        try {
            JsonNode root =
                    objectMapper.readTree(body);

            JsonNode data =
                    root.has("data") ? root.get("data") : root.get("models");

            if (data == null || !data.isArray()) {
                return ids;
            }

            for (JsonNode entry : data) {
                JsonNode id = entry.get("id");

                if (id == null) {
                    id = entry.get("name");
                }

                if (id != null && !id.asText().isBlank()) {
                    ids.add(id.asText());
                }
            }

        } catch (Exception exception) {
            log.debug("Unable to parse provider model list: {}", exception.getMessage());
        }

        return ids;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        return current.getClass().getSimpleName()
                + (current.getMessage() == null ? "" : ": " + current.getMessage());
    }

    /**
     * Result of a real provider probe.
     *
     * @param providerReachable true only when the loopback provider answered
     * @param modelServed       true only when the configured model is served
     * @param status            responding / unreachable / skipped
     * @param servedModels      model identifiers reported by the provider
     * @param details           human readable, factual description
     */
    public record HealthStatus(
            boolean providerReachable,
            boolean modelServed,
            String status,
            List<String> servedModels,
            String details
    ) {
    }
}

package com.codeguardai.ai;

import com.codeguardai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies that AI provider availability is derived from a real provider
 * response and never from configuration alone.
 */
class AiProviderHealthProbeTest {

    private static final String MODEL = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf";

    private static AiProperties properties() {
        AiProperties properties = new AiProperties();
        properties.setBaseUrl("http://127.0.0.1:8081");
        properties.setModel(MODEL);
        return properties;
    }

    @Test
    void reportsUnreachableWhenTheProviderDoesNotAnswer() {

        AiProperties properties = properties();

        /*
         * Port 1 is never served, so this is a genuine connection failure.
         * The configured demo port (8081) must not be used here: the probe is
         * a real network call, so aiming it at 8081 makes this test depend on
         * whether a local llama-server happens to be running and turns a
         * passing suite into a failing one on a demo machine.
         */
        properties.setBaseUrl("http://127.0.0.1:1");

        AiProviderHealthProbe probe =
                new AiProviderHealthProbe(properties, new ObjectMapper());

        AiProviderHealthProbe.HealthStatus status = probe.probe();

        assertThat(status.providerReachable()).isFalse();
        assertThat(status.modelServed()).isFalse();
        assertThat(status.status()).isEqualTo("unreachable");
        assertThat(status.details()).contains("not reachable");
    }

    @Test
    void reportsSkippedWhenHealthCheckIsDisabled() {

        AiProperties properties = properties();
        properties.setHealthCheckEnabled(false);

        AiProviderHealthProbe.HealthStatus status =
                new AiProviderHealthProbe(properties, new ObjectMapper()).probe();

        assertThat(status.providerReachable()).isFalse();
        assertThat(status.modelServed()).isFalse();
        assertThat(status.status()).isEqualTo("skipped");
    }

    @Test
    void rejectsANonLoopbackProbeTarget() {

        AiProperties properties = new AiProperties();

        org.junit.jupiter.api.Assertions.assertThrows(
                SecurityException.class,
                () -> properties.setBaseUrl("http://api.openai.com/v1")
        );
    }

    @Test
    void parsesTheOpenAiCompatibleModelListFromARealResponseShape() {

        AiProperties properties = properties();

        String body = "{\"object\":\"list\",\"data\":[{\"id\":\""
                + MODEL
                + "\",\"object\":\"model\"}]}";

        assertThat(body).contains("\"data\"").contains(MODEL);
    }

    @Test
    void theProbeIssuesAPlainGetOnTheOpenAiCompatibleModelsPath() {

        AiProperties properties = properties();

        RestClient.Builder builder =
                RestClient.builder().baseUrl(properties.getBaseUrl());

        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        RestClient restClient = builder.build();

        server.expect(once(), requestTo("http://127.0.0.1:8081/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"object\":\"list\",\"data\":[{\"id\":\"" + MODEL + "\"}]}",
                        MediaType.APPLICATION_JSON
                ));

        String body =
                restClient.get()
                        .uri("/v1/models")
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(String.class);

        assertThat(body).contains(MODEL);

        server.verify();
    }
}

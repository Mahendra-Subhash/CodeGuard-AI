package com.codeguardai.ai;

import com.codeguardai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Spring context wires the llama.cpp HTTP provider as the
 * primary AiInferenceProvider and that the llama-server configuration is bound
 * from application.yml.
 */
@SpringBootTest
class AiProviderWiringTest {

    @Autowired
    private AiInferenceProvider aiInferenceProvider;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Test
    void httpLlamaServerProviderIsThePrimaryInferenceProvider() {

        assertThat(aiInferenceProvider)
                .isInstanceOf(HttpLocalAiInferenceProvider.class);

        assertThat(aiInferenceProvider.providerName())
                .isEqualTo("llama-server-http");
    }

    @Test
    void llamaServerConfigurationIsBoundFromApplicationYml() {

        assertThat(aiProperties.getProvider()).isEqualTo("llama-server");
        assertThat(aiProperties.getBaseUrl()).isEqualTo("http://127.0.0.1:8081");

        /*
         * The served model is a deployment choice rather than a contract: the
         * value must match the --alias passed to llama-server, and DEMO.md pins
         * qwen2.5-coder-1.5b-instruct-q4_k_m.gguf for the local demo. What this
         * test owns is the binding itself, so it compares against the YAML
         * instead of repeating a value that drifts whenever the model changes.
         */
        assertThat(aiProperties.getModel())
                .isNotBlank()
                .isEqualTo(
                        environment.getProperty("codeguard.ai.model")
                );

        assertThat(aiProperties.getRuntime()).isEqualTo("llama-cpp-server");
        assertThat(aiProperties.getTimeoutMs()).isEqualTo(30000);
        assertThat(aiProperties.getContextLength()).isEqualTo(2048);
        assertThat(aiProperties.isPrivacyMode()).isTrue();
    }

    @Test
    void bothProviderBeansRemainRegistered() {

        assertThat(applicationContext.containsBean("httpLocalAiInferenceProvider"))
                .isTrue();

        assertThat(applicationContext.containsBean("localHeuristicAiProvider"))
                .isTrue();
    }
}

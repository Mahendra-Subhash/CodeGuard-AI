package com.codeguardai.ai;

import com.codeguardai.config.AiProperties;
import com.codeguardai.domain.FixProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpLocalAiInferenceProviderTest {

    private AiProperties aiProperties;
    private ObjectMapper objectMapper;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private HttpLocalAiInferenceProvider provider;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.setBaseUrl("http://127.0.0.1:8081");
        aiProperties.setModel("test-local-model");
        aiProperties.setTimeoutMs(5000);

        objectMapper = new ObjectMapper();
        restClientBuilder = RestClient.builder().baseUrl(aiProperties.getBaseUrl());
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        RestClient restClient = restClientBuilder.build();

        provider = new HttpLocalAiInferenceProvider(aiProperties, objectMapper, restClient);
    }

    @Test
    void testPromptConstructionContainsAllRequiredContext() {
        AiAnalysisRequest request = new AiAnalysisRequest(
                "public class Vulnerable { void run() { Statement s = c.createStatement(); s.executeQuery(\"SELECT * FROM \" + u); } }",
                "Vulnerable.java",
                "PMD",
                "AvoidStringConcatenationInQuery",
                "Security",
                "HIGH",
                "User-controlled data is concatenated into a SQL statement.",
                "s.executeQuery(\"SELECT * FROM \" + u);",
                "Use PreparedStatement and bind parameters instead."
        );

        String prompt = provider.buildUserPrompt(request);

        assertThat(prompt).contains("Vulnerable.java");
        assertThat(prompt).contains("PMD");
        assertThat(prompt).contains("AvoidStringConcatenationInQuery");
        assertThat(prompt).contains("HIGH");
        assertThat(prompt).contains("User-controlled data is concatenated into a SQL statement.");
        assertThat(prompt).contains("s.executeQuery(\"SELECT * FROM \" + u);");
        assertThat(prompt).contains("public class Vulnerable");
    }

    @Test
    void testSuccessfulLlamaServerResponseParsing() {
        String modelContent = """
                {
                  "summary": "SQL injection vulnerability detected",
                  "explanation": "Untrusted user data is concatenated directly into SQL statement.",
                  "rootCause": "Direct string concatenation into SQL query",
                  "risk": "Remote database exfiltration or manipulation",
                  "recommendedFix": "Use PreparedStatement with parameterized query",
                  "correctedCode": "PreparedStatement ps = c.prepareStatement(\\"SELECT * FROM users WHERE u = ?\\");",
                  "confidence": "0.95"
                }
                """;

        String completionJson = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": %s
                      }
                    }
                  ]
                }
                """.formatted(objectMapper.valueToTree(modelContent).toString());

        mockServer.expect(requestTo("http://127.0.0.1:8081/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(completionJson, MediaType.APPLICATION_JSON));

        AiAnalysisRequest request = new AiAnalysisRequest(
                "code", "Demo.java", "PMD", "SQL", "Security", "HIGH", "SQL issue", "snippet", "ctx"
        );

        AiAnalysisResponse response = provider.analyze(request);

        mockServer.verify();
        assertThat(response.summary()).isEqualTo("SQL injection vulnerability detected");
        assertThat(response.explanation()).contains("Untrusted user data");
        assertThat(response.confidence()).isEqualTo("0.95");
        assertThat(response.correctedCode()).contains("PreparedStatement");
    }

    @Test
    void testMarkdownFencedJsonResponseParsing() {
        String modelContentWithFences = """
                ```json
                {
                  "summary": "NPE Risk",
                  "explanation": "Variable dereferenced without null guard.",
                  "rootCause": "Missing null check",
                  "risk": "Application crash",
                  "recommendedFix": "Check for null before dereference",
                  "correctedCode": "if (v != null) { v.run(); }",
                  "confidence": "uncalibrated"
                }
                ```
                """;

        String completionJson = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": %s
                      }
                    }
                  ]
                }
                """.formatted(objectMapper.valueToTree(modelContentWithFences).toString());

        mockServer.expect(requestTo("http://127.0.0.1:8081/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(completionJson, MediaType.APPLICATION_JSON));

        AiAnalysisRequest request = new AiAnalysisRequest(
                "code", "Demo.java", "PMD", "NPE", "Reliability", "HIGH", "NPE", "snippet", "ctx"
        );

        AiAnalysisResponse response = provider.analyze(request);

        mockServer.verify();
        assertThat(response.summary()).isEqualTo("NPE Risk");
        assertThat(response.confidence()).isEqualTo("uncalibrated");
    }

    @Test
    void testMalformedJsonRetrySucceedsOnSecondAttempt() {
        String malformedFirstAttempt = "Here is my advice: You should fix this null pointer issue. No JSON provided.";
        String validSecondAttempt = """
                {
                  "summary": "Resolved after strict retry",
                  "explanation": "Valid JSON provided on retry",
                  "rootCause": "Null pointer dereference",
                  "risk": "Crash",
                  "recommendedFix": "Add null check",
                  "correctedCode": "if (x != null) x.doSomething();",
                  "confidence": "0.90"
                }
                """;

        String completionJson1 = """
                { "choices": [ { "message": { "content": %s } } ] }
                """.formatted(objectMapper.valueToTree(malformedFirstAttempt).toString());

        String completionJson2 = """
                { "choices": [ { "message": { "content": %s } } ] }
                """.formatted(objectMapper.valueToTree(validSecondAttempt).toString());

        // First attempt (fails JSON parse)
        mockServer.expect(requestTo("http://127.0.0.1:8081/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(completionJson1, MediaType.APPLICATION_JSON));

        // Second attempt (retry succeeds)
        mockServer.expect(requestTo("http://127.0.0.1:8081/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(completionJson2, MediaType.APPLICATION_JSON));

        AiAnalysisRequest request = new AiAnalysisRequest(
                "code", "Demo.java", "PMD", "NPE", "Reliability", "HIGH", "NPE", "snippet", "ctx"
        );

        AiAnalysisResponse response = provider.analyze(request);

        mockServer.verify();
        assertThat(response.summary()).isEqualTo("Resolved after strict retry");
        assertThat(response.confidence()).isEqualTo("0.90");
    }
    @Test
    void testDoubleMalformedJsonReturnsDegradedResponseWithoutFakeConfidence() {
        String malformedContent = "Still just plain text, cannot parse";
        String completionJson = """
                { "choices": [ { "message": { "content": %s } } ] }
                """.formatted(objectMapper.valueToTree(malformedContent).toString());

        // First call
        mockServer.expect(requestTo("http://127.0.0.1:8081/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(completionJson, MediaType.APPLICATION_JSON));

        // Second retry call
        mockServer.expect(requestTo("http://127.0.0.1:8081/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(completionJson, MediaType.APPLICATION_JSON));

        AiAnalysisRequest request = new AiAnalysisRequest(
                "code", "Demo.java", "PMD", "NPE", "Reliability", "HIGH", "NPE", "snippet", "ctx"
        );

        AiAnalysisResponse response = provider.analyze(request);

        mockServer.verify();
        assertThat(response.summary()).contains("Local AI inference produced invalid JSON output");
        assertThat(response.confidence()).isEqualTo("uncalibrated");
        assertThat(response.confidence()).isNotEqualTo("0.88"); // Never fake 0.88
    }

    @Test
    void testServerUnavailableReturnsDegradedResponse() {
        mockServer.expect(requestTo("http://127.0.0.1:8081/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        AiAnalysisRequest request = new AiAnalysisRequest(
                "code", "Demo.java", "PMD", "NPE", "Reliability", "HIGH", "NPE", "snippet", "ctx"
        );

        AiAnalysisResponse response = provider.analyze(request);

        mockServer.verify();
        assertThat(response.summary()).contains("Local AI server returned error status");
        assertThat(response.confidence()).isEqualTo("uncalibrated");
    }

    @Test
    void testProposeFixBuildsDiffAndRationale() {
        String modelContent = """
                {
                  "summary": "Fix NPE",
                  "explanation": "Guarding dereference",
                  "rootCause": "Null value",
                  "risk": "Crash",
                  "recommendedFix": "Add if guard",
                  "correctedCode": "if (user != null) user.read();",
                  "confidence": "0.99"
                }
                """;

        String completionJson = """
                { "choices": [ { "message": { "content": %s } } ] }
                """.formatted(objectMapper.valueToTree(modelContent).toString());

        mockServer.expect(requestTo("http://127.0.0.1:8081/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(completionJson, MediaType.APPLICATION_JSON));

        AiAnalysisRequest request = new AiAnalysisRequest(
                "code", "Demo.java", "PMD", "NPE", "Reliability", "HIGH", "NPE", "user.read();", "ctx"
        );

        FixProposal proposal = provider.proposeFix(request);

        mockServer.verify();
        assertThat(proposal.originalCode()).isEqualTo("user.read();");
        assertThat(proposal.proposedCode()).isEqualTo("if (user != null) user.read();");
        assertThat(proposal.diff()).contains("- user.read();");
        assertThat(proposal.diff()).contains("+ if (user != null) user.read();");
        assertThat(proposal.rationale()).isEqualTo("Add if guard");
    }

    @Test
    void testLocalhostOnlyEndpointValidationRejectsExternalUrls() {
        assertThatThrownBy(() -> AiProperties.validateLoopbackUrl("http://api.openai.com/v1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("must strictly target 127.0.0.1 or localhost");

        assertThatThrownBy(() -> AiProperties.validateLoopbackUrl("http://192.168.1.100:8080"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("must strictly target 127.0.0.1 or localhost");

        // Allowed
        AiProperties.validateLoopbackUrl("http://127.0.0.1:8081");
        AiProperties.validateLoopbackUrl("http://localhost:8081");
    }
}


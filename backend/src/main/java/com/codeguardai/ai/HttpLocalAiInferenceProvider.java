package com.codeguardai.ai;

import com.codeguardai.config.AiProperties;
import com.codeguardai.domain.FixProposal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Primary
public class HttpLocalAiInferenceProvider implements AiInferenceProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpLocalAiInferenceProvider.class);

    private static final String SYSTEM_PROMPT =
            "You are a Java code review assistant. Analyze the supplied static-analysis finding and surrounding Java code. " +
            "Return ONLY one valid JSON object. Do not use Markdown fences. Do not include explanatory prose outside the JSON object.";

    private static final String STRICT_RETRY_SYSTEM_PROMPT =
            "You are a Java code review assistant. Analyze the supplied static-analysis finding and surrounding Java code. " +
            "CRITICAL: Return ONLY a raw JSON object with keys: summary, explanation, rootCause, risk, recommendedFix, correctedCode, confidence. " +
            "Do NOT use Markdown formatting or code fences. Do NOT write anything before or after the JSON.";

    private final AiProperties aiProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * Production constructor used by Spring. It is explicitly marked with
     * {@link Autowired} because the class also exposes a RestClient-injectable
     * constructor for tests, and Spring requires an explicit hint when more than
     * one constructor is present.
     */
    @Autowired
    public HttpLocalAiInferenceProvider(AiProperties aiProperties, ObjectMapper objectMapper) {
        this(aiProperties, objectMapper, buildDefaultRestClient(aiProperties));
    }

    /**
     * Test friendly constructor that accepts a pre-configured RestClient (for
     * example one bound to MockRestServiceServer).
     */
    public HttpLocalAiInferenceProvider(AiProperties aiProperties, ObjectMapper objectMapper, RestClient restClient) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        AiProperties.validateLoopbackUrl(aiProperties.getBaseUrl());
    }

    private static RestClient buildDefaultRestClient(AiProperties properties) {
        AiProperties.validateLoopbackUrl(properties.getBaseUrl());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = properties.getTimeoutMs() > 0 ? properties.getTimeoutMs() : 30000;
        factory.setConnectTimeout(Duration.ofMillis(Math.min(timeout, 10000)));
        factory.setReadTimeout(Duration.ofMillis(timeout));

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public String providerName() {
        return "llama-server-http";
    }


    @Override
    public AiAnalysisResponse analyze(AiAnalysisRequest request) {
        AiProperties.validateLoopbackUrl(aiProperties.getBaseUrl());

        String userPrompt = buildUserPrompt(request);

        try {
            String rawResponse = executeChatCompletion(SYSTEM_PROMPT, userPrompt);
            ParsedResponse parsed = parseModelOutput(rawResponse);
            if (parsed.isSuccess()) {
                return toResponse(parsed.node());
            }

            log.warn("First attempt failed to parse JSON from llama-server: {}. Retrying once with strict prompt...", parsed.errorMessage());
            String retryPrompt = userPrompt + "\n\nNotice: Your previous response was invalid. Return ONLY valid raw JSON.";
            String retryRawResponse = executeChatCompletion(STRICT_RETRY_SYSTEM_PROMPT, retryPrompt);
            ParsedResponse retryParsed = parseModelOutput(retryRawResponse);
            if (retryParsed.isSuccess()) {
                return toResponse(retryParsed.node());
            }

            log.error("Second attempt failed to parse JSON from llama-server. Raw output: {}", retryRawResponse);
            return buildDegradedResponse(
                    "Local AI inference produced invalid JSON output",
                    "The local model returned a response that could not be parsed as JSON even after a strict retry attempt. " +
                    "Details: " + retryParsed.errorMessage() + "\nRaw response excerpt: " + sanitizeForDisplay(retryRawResponse),
                    request
            );

        } catch (ResourceAccessException ex) {
            log.error("Failed to connect to local llama-server at {}: {}", aiProperties.getBaseUrl(), ex.getMessage());
            return buildDegradedResponse(
                    "Local AI server unavailable or timed out",
                    "Could not complete request to local llama-server at " + aiProperties.getBaseUrl() + ". " +
                    "Verify that 'llama-server' is running on 127.0.0.1. Error: " + ex.getMessage(),
                    request
            );
        } catch (RestClientResponseException ex) {
            log.error("Local llama-server returned HTTP {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            return buildDegradedResponse(
                    "Local AI server returned error status " + ex.getStatusCode(),
                    "The local llama-server returned an HTTP error. Response: " + ex.getResponseBodyAsString(),
                    request
            );
        } catch (SecurityException ex) {
            log.error("Security violation in AI client: {}", ex.getMessage());
            return buildDegradedResponse(
                    "Security validation blocked external connection",
                    ex.getMessage(),
                    request
            );
        } catch (Exception ex) {
            log.error("Unexpected error during local AI inference", ex);
            return buildDegradedResponse(
                    "Unexpected error during local AI inference",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    request
            );
        }
    }

    @Override
    public FixProposal proposeFix(AiAnalysisRequest request) {
        AiAnalysisResponse analysis = analyze(request);
        String originalCode = request.snippet() != null && !request.snippet().isBlank()
                ? request.snippet()
                : request.sourceCode();

        String proposedCode = analysis.correctedCode() != null && !analysis.correctedCode().isBlank()
                ? analysis.correctedCode()
                : originalCode;

        String diff = buildDiff(originalCode, proposedCode);
        String rationale = analysis.recommendedFix() != null && !analysis.recommendedFix().isBlank()
                ? analysis.recommendedFix()
                : analysis.summary();

        return new FixProposal(originalCode, proposedCode, diff, rationale);
    }

    private String executeChatCompletion(String systemPrompt, String userPrompt) {
        Map<String, Object> payload = Map.of(
                "model", aiProperties.getModel() != null ? aiProperties.getModel() : "local-model",
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", aiProperties.getTemperature(),
                "max_tokens", 1024
        );

        ChatCompletionResponse response = restClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(ChatCompletionResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("llama-server returned an empty completion response");
        }

        String content = response.choices().get(0).message().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("llama-server returned an empty message content");
        }

        return content.trim();
    }

    public String buildUserPrompt(AiAnalysisRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Static Analysis Finding to Review:\n");
        if (request.fileName() != null && !request.fileName().isBlank()) {
            sb.append("- File: ").append(request.fileName()).append("\n");
        }
        sb.append("- Analyzer: ").append(safe(request.analyzer())).append("\n");
        sb.append("- Rule: ").append(safe(request.rule())).append("\n");
        sb.append("- Severity: ").append(safe(request.severity())).append("\n");
        sb.append("- Message: ").append(safe(request.message())).append("\n");

        if (request.snippet() != null && !request.snippet().isBlank()) {
            sb.append("- Flagged Code Snippet:\n```java\n").append(request.snippet()).append("\n```\n");
        }

        String focusedContext = extractRelevantContext(request.sourceCode(), request.snippet());
        if (!focusedContext.isBlank()) {
            sb.append("\nSurrounding Java Code Context:\n```java\n").append(focusedContext).append("\n```\n");
        }

        sb.append("\nRespond with a single JSON object containing exact keys:\n")
          .append("{\n")
          .append("  \"summary\": \"Concise one-sentence description of the issue\",\n")
          .append("  \"explanation\": \"Detailed technical explanation\",\n")
          .append("  \"rootCause\": \"Underlying cause of the defect\",\n")
          .append("  \"risk\": \"Security or operational impact\",\n")
          .append("  \"recommendedFix\": \"Specific guidance for remediation\",\n")
          .append("  \"correctedCode\": \"The corrected Java replacement code snippet\",\n")
          .append("  \"confidence\": \"0.95 or uncalibrated\"\n")
          .append("}");

        return sb.toString();
    }

    private String extractRelevantContext(String sourceCode, String snippet) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return "";
        }
        String[] lines = sourceCode.split("\\r?\\n");
        if (lines.length <= 40) {
            return sourceCode;
        }

        int targetLine = -1;
        if (snippet != null && !snippet.isBlank()) {
            String firstSnippetLine = snippet.split("\\r?\\n")[0].trim();
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(firstSnippetLine)) {
                    targetLine = i;
                    break;
                }
            }
        }

        if (targetLine == -1) {
            targetLine = lines.length / 2;
        }

        int start = Math.max(0, targetLine - 15);
        int end = Math.min(lines.length, targetLine + 15);

        List<String> window = new ArrayList<>();
        for (int i = start; i < end; i++) {
            window.add(lines[i]);
        }
        return String.join("\n", window);
    }

    public ParsedResponse parseModelOutput(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedResponse(null, false, "Response was empty");
        }

        String cleaned = cleanMarkdownFences(raw);
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (!node.isObject()) {
                return new ParsedResponse(null, false, "Parsed JSON is not a JSON object");
            }
            return new ParsedResponse(node, true, null);
        } catch (Exception e) {
            return new ParsedResponse(null, false, e.getMessage());
        }
    }

    private String cleanMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            } else {
                trimmed = trimmed.substring(3);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            trimmed = trimmed.trim();
        }

        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace > firstBrace) {
            trimmed = trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed.trim();
    }

    private AiAnalysisResponse toResponse(JsonNode node) {
        String summary = getTextField(node, "summary", "Issue detected by static analysis.");
        String explanation = getTextField(node, "explanation", "No detailed explanation provided by model.");
        String rootCause = getTextField(node, "rootCause", "Root cause not specified.");
        String risk = getTextField(node, "risk", "Impact not specified.");
        String recommendedFix = getTextField(node, "recommendedFix", "Review the issue according to best practices.");
        String correctedCode = getTextField(node, "correctedCode", "");
        String rawConfidence = getTextField(node, "confidence", "uncalibrated");
        String confidence = validateConfidence(rawConfidence);
        String verificationHint = "Re-scan the code to verify that the static analyzer no longer flags this finding.";

        return new AiAnalysisResponse(
                summary,
                explanation,
                rootCause,
                risk,
                recommendedFix,
                correctedCode,
                confidence,
                verificationHint
        );
    }

    private String validateConfidence(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("uncalibrated")) {
            return "uncalibrated";
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            if (parsed >= 0.0 && parsed <= 1.0) {
                return String.format(java.util.Locale.ROOT, "%.2f", parsed);
            }
        } catch (NumberFormatException ignored) {
        }
        return "uncalibrated";
    }

    private AiAnalysisResponse buildDegradedResponse(String summary, String details, AiAnalysisRequest request) {
        String safeSnippet = request.snippet() != null && !request.snippet().isBlank()
                ? request.snippet()
                : request.sourceCode();

        return new AiAnalysisResponse(
                summary,
                details,
                "Local inference service was unable to generate an explanation.",
                "Manual review required.",
                "Inspect the flagged code snippet and apply manual correction.",
                safeSnippet != null ? safeSnippet : "",
                "uncalibrated",
                "Verify local llama-server process status on 127.0.0.1."
        );
    }

    private String buildDiff(String originalCode, String correctedCode) {
        if (originalCode == null || originalCode.isBlank()) {
            return "+ " + (correctedCode != null ? correctedCode : "");
        }
        if (correctedCode == null || correctedCode.isBlank() || originalCode.equals(correctedCode)) {
            return "No modification proposed";
        }
        return "- " + originalCode.replace("\n", "\n- ") + "\n+ " + correctedCode.replace("\n", "\n+ ");
    }

    private String getTextField(JsonNode node, String fieldName, String fallback) {
        if (node.hasNonNull(fieldName)) {
            String text = node.get(fieldName).asText();
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        }
        return fallback;
    }

    private String safe(String val) {
        return val != null ? val : "N/A";
    }

    private String sanitizeForDisplay(String text) {
        if (text == null) return "";
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    public record ParsedResponse(JsonNode node, boolean isSuccess, String errorMessage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionResponse(List<Choice> choices) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Choice(Message message) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Message(String content) {}
    }
}


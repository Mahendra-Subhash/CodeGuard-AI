package com.codeguardai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@ConfigurationProperties(prefix = "codeguard.ai")
public class AiProperties {
    private String provider = "llama-server";
    private String baseUrl = "http://127.0.0.1:8081";
    private String model = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf";
    private String runtime = "llama-cpp-server";
    private int timeoutMs = 30000;
    private int contextLength = 2048;
    private double temperature = 0.2;
    private boolean privacyMode = true;
    private boolean healthCheckEnabled = true;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) {
        validateLoopbackUrl(baseUrl);
        this.baseUrl = baseUrl;
    }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getContextLength() { return contextLength; }
    public void setContextLength(int contextLength) { this.contextLength = contextLength; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public boolean isPrivacyMode() { return privacyMode; }
    public void setPrivacyMode(boolean privacyMode) { this.privacyMode = privacyMode; }
    public boolean isHealthCheckEnabled() { return healthCheckEnabled; }
    public void setHealthCheckEnabled(boolean healthCheckEnabled) { this.healthCheckEnabled = healthCheckEnabled; }

    public static void validateLoopbackUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("AI base URL cannot be blank");
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || (!host.equals("127.0.0.1") && !host.equalsIgnoreCase("localhost"))) {
                throw new SecurityException("Security violation: Local AI base URL must strictly target 127.0.0.1 or localhost. Configured: " + url);
            }
        } catch (IllegalArgumentException | SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid AI base URL: " + url, e);
        }
    }
}


package com.codeguardai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "codeguard.qualcomm")
public class QualcommProperties {
    private String deploymentMode = "development";
    private String modelId = "configurable-model-id";
    private String runtime = "qualcomm-ai-runtime";
    private String executionTarget = "snapdragon-hardware-or-qairt";
    private boolean npuExecutionVerified = false;
    private boolean enabled = false;

    /*
     * GenieX integration settings.
     *
     * Every value below is either a configured intent or the location of an
     * evidence artifact. None of them is accepted as proof of NPU execution by
     * itself - see QualcommVerificationService for the evidence rules.
     */
    private final Server server = new Server();
    private final Verification verification = new Verification();
    private final Cli cli = new Cli();

    public Server getServer() { return server; }
    public Verification getVerification() { return verification; }
    public Cli getCli() { return cli; }

    public String getDeploymentMode() { return deploymentMode; }
    public void setDeploymentMode(String deploymentMode) { this.deploymentMode = deploymentMode; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }
    public String getExecutionTarget() { return executionTarget; }
    public void setExecutionTarget(String executionTarget) { this.executionTarget = executionTarget; }
    public boolean isNpuExecutionVerified() { return npuExecutionVerified; }
    public void setNpuExecutionVerified(boolean npuExecutionVerified) { this.npuExecutionVerified = npuExecutionVerified; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * The GenieX local server ({@code geniex serve}) exposes an OpenAI compatible
     * API on the loopback interface only ({@code http://127.0.0.1:18181}).
     *
     * <p>The URL is validated with {@link AiProperties#validateLoopbackUrl(String)}
     * so a remote address - for example a raw Qualcomm Device Cloud endpoint - is
     * rejected at startup. A remote Snapdragon device must be reached through an
     * SSH local port forward, which keeps the loopback invariant intact and keeps
     * the unauthenticated GenieX API from ever being exposed.
     */
    public static class Server {
        /** When false, no request is ever sent to the GenieX server. */
        private boolean enabled = false;
        private String baseUrl = "http://127.0.0.1:18181";
        /** Model identifier as reported by GET /v1/models, e.g. org/repo[:precision]. */
        private String model = "";
        /** Compute unit the deployment intends to run on: npu | gpu | cpu | hybrid. */
        private String computeUnit = "npu";
        private int connectTimeoutMs = 1500;
        private int readTimeoutMs = 5000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) {
            AiProperties.validateLoopbackUrl(baseUrl);
            this.baseUrl = baseUrl;
        }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getComputeUnit() { return computeUnit; }
        public void setComputeUnit(String computeUnit) { this.computeUnit = computeUnit; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    /**
     * Evidence rules for the "Snapdragon/NPU execution verified" claim.
     *
     * <p>{@code mode} selects which evidence is required:
     * <ul>
     *   <li>{@code both} (default) - a live GenieX server round trip and a GenieX
     *       benchmark artifact that proves the NPU compute unit must both be
     *       present, and both must name the same model.</li>
     *   <li>{@code http} - only the live server round trip is required. A server
     *       response cannot reveal which compute unit ran, so this mode reports
     *       the weaker {@code server-reachable} tier and never a verified NPU.</li>
     *   <li>{@code artifact} - only the benchmark artifact is required.</li>
     *   <li>{@code disabled} - verification is off and the runtime is never
     *       reported as verified.</li>
     * </ul>
     */
    public static class Verification {
        private String mode = "both";
        /** Path of the {@code geniex-bench --output-json} artifact. */
        private String artifactPath = "artifacts/qualcomm/geniex-bench.json";
        /** Compute unit the artifact must report to support the NPU claim. */
        private String requireDevice = "npu";
        /** Artifacts older than this are not accepted as current evidence. */
        private int maxArtifactAgeDays = 30;
        /**
         * Set false to skip the chat completion round trip. Mode {@code both}
         * cannot pass while this is false; that is reported instead of assumed.
         */
        private boolean inferenceProbeEnabled = true;
        /**
         * Optional chipset label, for example {@code Snapdragon X Elite}. When
         * set, an artifact naming a different chipset is rejected.
         */
        private String chipsetLabel = "";
        /**
         * Allow evidence that describes a remote Snapdragon device reached through
         * an SSH tunnel while CodeGuard itself runs on a non ARM64 workstation.
         *
         * <p>False by default: the local architecture gate then applies, so an
         * x86_64 machine can never report a verified Snapdragon runtime. When set
         * to true, the artifact must additionally name the device (a chipset or a
         * host label), so the reported evidence can never be read as a claim that
         * this workstation owns an NPU.
         */
        private boolean allowRemoteDevice = false;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getArtifactPath() { return artifactPath; }
        public void setArtifactPath(String artifactPath) { this.artifactPath = artifactPath; }
        public String getRequireDevice() { return requireDevice; }
        public void setRequireDevice(String requireDevice) { this.requireDevice = requireDevice; }
        public int getMaxArtifactAgeDays() { return maxArtifactAgeDays; }
        public void setMaxArtifactAgeDays(int maxArtifactAgeDays) { this.maxArtifactAgeDays = maxArtifactAgeDays; }
        public boolean isInferenceProbeEnabled() { return inferenceProbeEnabled; }
        public void setInferenceProbeEnabled(boolean inferenceProbeEnabled) { this.inferenceProbeEnabled = inferenceProbeEnabled; }
        public String getChipsetLabel() { return chipsetLabel; }
        public void setChipsetLabel(String chipsetLabel) { this.chipsetLabel = chipsetLabel; }
        public boolean isAllowRemoteDevice() { return allowRemoteDevice; }
        public void setAllowRemoteDevice(boolean allowRemoteDevice) { this.allowRemoteDevice = allowRemoteDevice; }
    }

    /**
     * Where to look for the {@code geniex} launcher. Detection is a file
     * existence check only: no GenieX process is started and no version command
     * is executed, so detection can never block a request or download a model.
     */
    public static class Cli {
        /** Explicit path to the launcher; overrides the PATH search when set. */
        private String executable = "";
        /** Extra directories searched for the launcher. */
        private List<String> extraSearchPaths = new ArrayList<>();

        public String getExecutable() { return executable; }
        public void setExecutable(String executable) { this.executable = executable; }
        public List<String> getExtraSearchPaths() { return extraSearchPaths; }
        public void setExtraSearchPaths(List<String> extraSearchPaths) {
            this.extraSearchPaths = extraSearchPaths == null ? new ArrayList<>() : extraSearchPaths;
        }
    }

    /** Normalized view of {@link Verification#getMode()}. */
    public enum VerificationMode {
        BOTH, HTTP, ARTIFACT, DISABLED, UNKNOWN;

        public static VerificationMode from(String value) {
            if (value == null || value.isBlank()) {
                return BOTH;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "both", "http+artifact", "artifact+http" -> BOTH;
                case "http", "server", "http-only" -> HTTP;
                case "artifact", "bench", "artifact-only" -> ARTIFACT;
                case "disabled", "off", "none" -> DISABLED;
                default -> UNKNOWN;
            };
        }
    }
}

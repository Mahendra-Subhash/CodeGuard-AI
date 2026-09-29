package com.codeguardai.api;

import com.codeguardai.ai.AiProviderHealthProbe;
import com.codeguardai.ai.qualcomm.QualcommAiAdapter;
import com.codeguardai.ai.qualcomm.QualcommRuntimeStatus;
import com.codeguardai.config.AiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class RuntimeController {

    private final AiProperties aiProperties;
    private final QualcommAiAdapter qualcommAiAdapter;
    private final AiProviderHealthProbe aiProviderHealthProbe;

    public RuntimeController(
            AiProperties aiProperties,
            QualcommAiAdapter qualcommAiAdapter,
            AiProviderHealthProbe aiProviderHealthProbe) {
        this.aiProperties = aiProperties;
        this.qualcommAiAdapter = qualcommAiAdapter;
        this.aiProviderHealthProbe = aiProviderHealthProbe;
    }

    @GetMapping("/api/runtime")
    public Map<String, Object> runtime() {

        QualcommRuntimeStatus qualcommStatus = qualcommAiAdapter.runtimeStatus();

        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("provider", aiProperties.getProvider());
        payload.put("model", aiProperties.getModel());
        payload.put("runtime", aiProperties.getRuntime());
        payload.put("timeoutMs", aiProperties.getTimeoutMs());
        payload.put("contextLength", aiProperties.getContextLength());
        payload.put("privacyMode", aiProperties.isPrivacyMode());

        /*
         * Verified local AI provider status.
         *
         * "configured" is a configuration fact; "reachable" and "modelServed"
         * come from a real request to the loopback provider. Availability is
         * never reported from configuration alone.
         */
        AiProviderHealthProbe.HealthStatus health =
                aiProviderHealthProbe.probe();

        payload.put("aiConfigured", true);
        payload.put("aiBaseUrl", aiProperties.getBaseUrl());
        payload.put("aiProviderReachable", health.providerReachable());
        payload.put("aiModelServed", health.modelServed());
        payload.put("aiStatus", health.status());
        payload.put("aiServedModels", health.servedModels());
        payload.put("aiDetails", health.details());

        /*
         * Qualcomm runtime capability report.
         *
         * Configured values are reported as configuration, detected values as
         * facts, and availability is only reported when hardware execution was
         * actually verified.
         */
        payload.put("qualcommAdapter", qualcommAiAdapter.adapterName());
        payload.put("qualcommDeploymentMode", qualcommStatus.deploymentMode());
        payload.put("qualcommModelId", qualcommStatus.modelId());
        payload.put("qualcommRuntime", qualcommStatus.runtime());
        payload.put("qualcommExecutionTarget", qualcommStatus.executionTarget());
        payload.put("qualcommAvailable", qualcommStatus.available());
        payload.put("qualcommNpuVerified", qualcommStatus.npuExecutionVerified());
        payload.put("qualcommOs", qualcommStatus.operatingSystem());
        payload.put("qualcommOsVersion", qualcommStatus.osVersion());
        payload.put("qualcommCpuArchitecture", qualcommStatus.cpuArchitecture());
        payload.put("qualcommDetectedArchitecture", qualcommStatus.detectedArchitecture());
        payload.put("qualcommArm64Host", qualcommStatus.arm64HostDetected());
        payload.put("qualcommQnnRuntimePresent", qualcommStatus.qnnRuntimeComponentsPresent());
        payload.put("qualcommConfiguredExecutionVerified", qualcommStatus.configuredExecutionVerification());
        payload.put("qualcommDetectedComponentLocations", qualcommStatus.detectedComponentLocations());
        payload.put("qualcommSearchedComponentLocations", qualcommStatus.searchedComponentLocations());
        payload.put("qualcommDetectionNotes", qualcommStatus.detectionNotes());
        payload.put("qualcommDetails", qualcommStatus.details());

        /*
         * GenieX verification detail. The tier says how far evidence got, the
         * reason codes name the gates that did not pass, and the evidence record
         * carries what was observed (server, artifact, timings). Existing keys
         * above are unchanged so current clients keep working.
         */
        payload.put("qualcommGeniexCliDetected", qualcommStatus.geniexCliDetected());
        payload.put("qualcommGeniexCliLocations", qualcommStatus.geniexCliLocations());
        payload.put("qualcommVerificationTier", qualcommStatus.verificationTier());
        payload.put("qualcommVerificationReasons", qualcommStatus.verificationReasons());
        payload.put("qualcommVerificationMessage", qualcommStatus.verificationMessage());
        payload.put("qualcommVerificationEvidence", qualcommStatus.verificationEvidence());

        return payload;
    }
}

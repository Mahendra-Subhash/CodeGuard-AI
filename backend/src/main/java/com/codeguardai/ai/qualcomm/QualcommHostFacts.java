package com.codeguardai.ai.qualcomm;

import java.util.List;

/**
 * Factual observations about the current host machine and the Qualcomm runtime
 * components that are actually present on it.
 *
 * This record carries observations only. It never carries claims such as
 * "NPU acceleration available" because such a claim requires verified hardware
 * execution, which detection alone cannot establish.
 *
 * @param operatingSystem          value reported by the JVM (os.name)
 * @param osVersion                value reported by the JVM (os.version)
 * @param cpuArchitecture          raw value reported by the JVM (os.arch)
 * @param detectedArchitecture     normalized architecture of {@code cpuArchitecture}
 * @param arm64HostDetected        true only when the detected architecture is ARM64
 * @param qnnRuntimeComponentsPresent true only when QNN/QAIRT/SNPE components were found
 * @param detectedComponentLocations  locations where runtime components were found
 * @param searchedComponentLocations  locations that were inspected
 * @param detectionNotes           factual notes explaining what was observed
 */
public record QualcommHostFacts(
        String operatingSystem,
        String osVersion,
        String cpuArchitecture,
        String detectedArchitecture,
        boolean arm64HostDetected,
        boolean qnnRuntimeComponentsPresent,
        List<String> detectedComponentLocations,
        List<String> searchedComponentLocations,
        List<String> detectionNotes
) {

    public static final String ARCHITECTURE_X86_64 = "x86_64";
    public static final String ARCHITECTURE_X86_32 = "x86_32";
    public static final String ARCHITECTURE_ARM64 = "aarch64";
    public static final String ARCHITECTURE_ARM32 = "arm32";
    public static final String ARCHITECTURE_UNKNOWN = "unknown";
}

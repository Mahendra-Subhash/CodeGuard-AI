package com.codeguardai.ai.qualcomm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemQualcommRuntimeDetectorTest {

    @Test
    void normalizesKnownCpuArchitectures() {

        assertThat(SystemQualcommRuntimeDetector.normalizeArchitecture("amd64"))
                .isEqualTo(QualcommHostFacts.ARCHITECTURE_X86_64);

        assertThat(SystemQualcommRuntimeDetector.normalizeArchitecture("x86_64"))
                .isEqualTo(QualcommHostFacts.ARCHITECTURE_X86_64);

        assertThat(SystemQualcommRuntimeDetector.normalizeArchitecture("x86"))
                .isEqualTo(QualcommHostFacts.ARCHITECTURE_X86_32);

        assertThat(SystemQualcommRuntimeDetector.normalizeArchitecture("i686"))
                .isEqualTo(QualcommHostFacts.ARCHITECTURE_X86_32);

        assertThat(SystemQualcommRuntimeDetector.normalizeArchitecture("aarch64"))
                .isEqualTo(QualcommHostFacts.ARCHITECTURE_ARM64);

        assertThat(SystemQualcommRuntimeDetector.normalizeArchitecture("ARM64"))
                .isEqualTo(QualcommHostFacts.ARCHITECTURE_ARM64);

        assertThat(SystemQualcommRuntimeDetector.normalizeArchitecture("armv7l"))
                .isEqualTo(QualcommHostFacts.ARCHITECTURE_ARM32);

        assertThat(SystemQualcommRuntimeDetector.normalizeArchitecture("sparc"))
                .isEqualTo("sparc");

        assertThat(SystemQualcommRuntimeDetector.normalizeArchitecture(null))
                .isEqualTo(QualcommHostFacts.ARCHITECTURE_UNKNOWN);

        assertThat(SystemQualcommRuntimeDetector.normalizeArchitecture("   "))
                .isEqualTo(QualcommHostFacts.ARCHITECTURE_UNKNOWN);
    }

    @Test
    void reportsFactsAboutTheCurrentHostWithoutClaimingUnverifiedComponents() {

        QualcommHostFacts facts =
                new SystemQualcommRuntimeDetector().detect();

        assertThat(facts.operatingSystem()).isNotBlank();
        assertThat(facts.osVersion()).isNotBlank();
        assertThat(facts.cpuArchitecture()).isNotBlank();
        assertThat(facts.detectedArchitecture()).isNotBlank();

        assertThat(facts.detectedArchitecture())
                .isEqualTo(
                        SystemQualcommRuntimeDetector.normalizeArchitecture(
                                facts.cpuArchitecture()
                        )
                );

        assertThat(facts.arm64HostDetected())
                .isEqualTo(
                        QualcommHostFacts.ARCHITECTURE_ARM64.equals(facts.detectedArchitecture())
                );

        assertThat(facts.searchedComponentLocations()).isNotEmpty();

        assertThat(facts.detectionNotes())
                .anyMatch(note -> note.contains(facts.cpuArchitecture()));

        if (facts.qnnRuntimeComponentsPresent()) {
            assertThat(facts.detectedComponentLocations()).isNotEmpty();
        } else {
            assertThat(facts.detectedComponentLocations()).isEmpty();
            assertThat(facts.detectionNotes())
                    .anyMatch(note -> note.toLowerCase().contains("no qnn"));
        }
    }

    @Test
    void nonArm64HostIsReportedAsUnableToVerifySnapdragonExecution() {

        String rawArchitecture = System.getProperty("os.arch");

        if (!QualcommHostFacts.ARCHITECTURE_ARM64.equals(
                SystemQualcommRuntimeDetector.normalizeArchitecture(rawArchitecture)
        )) {

            QualcommHostFacts facts =
                    new SystemQualcommRuntimeDetector().detect();

            assertThat(facts.arm64HostDetected()).isFalse();
            assertThat(facts.detectionNotes())
                    .anyMatch(note -> note.contains("is not ARM64"));
        }
    }
}

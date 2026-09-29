package com.codeguardai.ai.qualcomm;

import com.codeguardai.config.QualcommProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code geniex-bench --output-json} schema is not published, so the reader
 * must find values by alias and must say so when it cannot. These tests pin that
 * behaviour: values are located wherever they plausibly live, and missing values
 * stay missing instead of being guessed.
 */
class GenieXBenchArtifactReaderTest {

    @TempDir
    Path directory;

    @Test
    void readsValuesFromNestedKeysAndKeepsNumericStatisticsVerbatim() throws IOException {

        String body = """
                {
                  "run": {
                    "compute_unit": "npu",
                    "plugin": "llama.cpp-qt"
                  },
                  "model_id": "qualcommiq/Llama-3.1-8B-Instruct:8b-precision-rr-20250504",
                  "chipset": "Snapdragon X Elite",
                  "host": "sqd-x-elite-01",
                  "captured_at": "%s",
                  "iterations": 5,
                  "stats": {
                    "tokens_per_second": 31.5,
                    "ttft_ms": 120,
                    "device": "should-not-win-over-the-shallow-key"
                  }
                }
                """.formatted(Instant.now().minus(2, ChronoUnit.HOURS).toString());

        GenieXBenchArtifact artifact = reader().read(write("bench.json", body).toString());

        assertThat(artifact.present()).isTrue();
        assertThat(artifact.parseable()).isTrue();
        assertThat(artifact.computeUnit()).isEqualTo("npu");
        assertThat(artifact.plugin()).isEqualTo("llama.cpp-qt");
        assertThat(artifact.model())
                .isEqualTo("qualcommiq/Llama-3.1-8B-Instruct:8b-precision-rr-20250504");
        assertThat(artifact.chipset()).isEqualTo("Snapdragon X Elite");
        assertThat(artifact.host()).isEqualTo("sqd-x-elite-01");
        assertThat(artifact.timestampSource()).isEqualTo("artifact");
        assertThat(artifact.ageDays()).isZero();
        assertThat(artifact.statistics())
                .containsEntry("stats.tokens_per_second", 31.5)
                .containsEntry("stats.ttft_ms", 120)
                .doesNotContainKey("iterations");
        assertThat(artifact.readError()).isNull();
    }

    @Test
    void reportsAMissingArtifactInsteadOfInventingValues() {

        GenieXBenchArtifact artifact =
                reader().read(directory.resolve("absent.json").toString());

        assertThat(artifact.present()).isFalse();
        assertThat(artifact.parseable()).isFalse();
        assertThat(artifact.computeUnit()).isNull();
        assertThat(artifact.readError())
                .isEqualTo(QualcommVerificationTiers.ARTIFACT_MISSING);
        assertThat(artifact.detail()).contains("verify-geniex-npu.ps1");
    }

    @Test
    void reportsAnUnreadableArtifactRatherThanPartialEvidence() throws IOException {

        GenieXBenchArtifact artifact =
                reader().read(write("broken.json", "{ this is not json").toString());

        assertThat(artifact.present()).isTrue();
        assertThat(artifact.parseable()).isFalse();
        assertThat(artifact.computeUnit()).isNull();
        assertThat(artifact.readError())
                .isEqualTo(QualcommVerificationTiers.ARTIFACT_UNREADABLE);
    }

    @Test
    void fallsBackToTheFileSystemTimestampWhenTheArtifactRecordsNone() throws IOException {

        GenieXBenchArtifact artifact =
                reader().read(write("plain.json", "{\"device\":\"npu\"}").toString());

        assertThat(artifact.computeUnit()).isEqualTo("npu");
        assertThat(artifact.timestampSource()).isEqualTo("file-system");
        assertThat(artifact.ageDays()).isZero();
    }

    @Test
    void reportsAnUnknownAgeWhenNoTimestampIsAvailableAtAll() {

        /*
         * A NUL character is not a legal path character on any supported file
         * system, so this exercises the invalid-path branch everywhere.
         */
        GenieXBenchArtifact artifact = reader().read("bench\u0000descriptor.json");

        assertThat(artifact.present()).isFalse();
        assertThat(artifact.ageDays()).isNull();
        assertThat(artifact.detail()).contains("not a valid path");
    }

    private GenieXBenchArtifactReader reader() {
        return new GenieXBenchArtifactReader(new QualcommProperties(), new ObjectMapper());
    }

    private Path write(String name, String body) throws IOException {
        Path path = directory.resolve(name);
        Files.writeString(path, body, StandardCharsets.UTF_8);
        return path;
    }
}

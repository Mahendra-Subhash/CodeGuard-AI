package com.codeguardai.ai.qualcomm;

import com.codeguardai.config.QualcommProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a {@code geniex-bench --output-json} artifact from disk.
 *
 * <p>Qualcomm does not publish the schema of that file, so nothing here is bound
 * to fixed types. Values are located by tolerant, shallowest-first key lookup
 * across a set of plausible aliases, which is also why
 * {@code scripts/verify-geniex-npu.ps1} normalizes a real capture into the small
 * documented shape described in {@code docs/evidence/README.md}. Anything that
 * cannot be found is reported as missing instead of guessed.
 *
 * <p>The artifact is only ever read. It is never written by this process, and an
 * unreadable, stale or contradicting artifact is never treated as evidence.
 */
@Component
public class GenieXBenchArtifactReader {

    private static final Logger log = LoggerFactory.getLogger(GenieXBenchArtifactReader.class);

    private static final String[] DEVICE_KEYS = {
            "device", "compute_unit", "computeUnit", "target_device", "hw_accelerator"
    };

    private static final String[] PLUGIN_KEYS = {
            "plugin", "runtime", "engine", "backend"
    };

    private static final String[] MODEL_KEYS = {
            "model", "model_id", "modelId", "model-name", "model_name",
            "modelName", "served_model_id", "checkpoint"
    };

    private static final String[] CHIPSET_KEYS = {
            "chipset", "soc", "chip", "target_platform", "platform"
    };

    private static final String[] HOST_KEYS = {
            "host", "hostname", "machine", "node", "device_serial"
    };

    private static final String[] TIMESTAMP_KEYS = {
            "captured_at", "capturedAt", "captured", "generated_at", "generatedAt",
            "timestamp", "created_at", "createdAt", "date", "ended_at", "finished_at"
    };

    private static final String[] STATISTIC_MARKERS = {
            "token", "tok", "ttft", "latency", "throughput", "time", "ms",
            "p50", "p90", "p99", "tps", "gen"
    };

    private static final int MAX_VISITED_NODES = 4000;
    private static final int MAX_STATISTICS = 40;

    private final QualcommProperties qualcommProperties;
    private final ObjectMapper objectMapper;

    public GenieXBenchArtifactReader(
            QualcommProperties qualcommProperties,
            ObjectMapper objectMapper
    ) {
        this.qualcommProperties = qualcommProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads the artifact configured through
     * {@code codeguard.qualcomm.verification.artifact-path}.
     *
     * @return the artifact contents, or a factual "missing"/"unreadable" result
     */
    public GenieXBenchArtifact read() {
        QualcommProperties.Verification settings = qualcommProperties.getVerification();
        return read(settings.getArtifactPath());
    }

    /**
     * @param configuredPath artifact path to read; relative paths resolve against
     *                       the working directory of the backend process
     * @return the artifact contents together with the age of its evidence
     */
    public GenieXBenchArtifact read(String configuredPath) {

        Path path = resolve(configuredPath);

        if (path == null) {
            return GenieXBenchArtifact.missing(
                    String.valueOf(configuredPath),
                    "The configured artifact path '" + configuredPath
                            + "' is not a valid path on this file system."
            );
        }

        String displayPath = path.toAbsolutePath().normalize().toString();

        if (!Files.isRegularFile(path)) {
            return GenieXBenchArtifact.missing(
                    displayPath,
                    "No GenieX benchmark artifact exists at " + displayPath
                            + ". Run scripts/verify-geniex-npu.ps1 on the Snapdragon device"
                            + " to create it."
            );
        }

        String body;

        try {
            body = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException exception) {
            log.debug("GenieX artifact read failed: {}", exception.getMessage());
            return GenieXBenchArtifact.unreadable(
                    displayPath,
                    QualcommVerificationTiers.ARTIFACT_UNREADABLE,
                    "The GenieX benchmark artifact at " + displayPath
                            + " could not be read: " + exception.getMessage()
            );
        }

        JsonNode root;

        try {
            root = objectMapper.readTree(body);
        } catch (Exception exception) {
            return GenieXBenchArtifact.unreadable(
                    displayPath,
                    QualcommVerificationTiers.ARTIFACT_UNREADABLE,
                    "The GenieX benchmark artifact at " + displayPath
                            + " does not contain valid JSON: " + exception.getMessage()
            );
        }

        if (root == null || !root.isObject()) {
            return GenieXBenchArtifact.unreadable(
                    displayPath,
                    QualcommVerificationTiers.ARTIFACT_UNREADABLE,
                    "The GenieX benchmark artifact at " + displayPath
                            + " does not contain a JSON object."
            );
        }

        TimestampSource timestamp = readTimestamp(root, path);

        return new GenieXBenchArtifact(
                true,
                true,
                displayPath,
                findFirstText(root, DEVICE_KEYS),
                findFirstText(root, PLUGIN_KEYS),
                findFirstText(root, MODEL_KEYS),
                findFirstText(root, CHIPSET_KEYS),
                findFirstText(root, HOST_KEYS),
                timestamp.text(),
                timestamp.source(),
                ageDays(timestamp.instant(), path),
                readStatistics(root),
                null,
                "Read the GenieX benchmark artifact at " + displayPath + "."
        );
    }


    /**
     * @param text    the timestamp exactly as the artifact wrote it, or null
     * @param source  {@code artifact} or {@code file-system}
     * @param instant parsed instant, or null when neither source provided one
     */
    record TimestampSource(String text, String source, Instant instant) {
    }

    private TimestampSource readTimestamp(JsonNode root, Path path) {

        String text = findFirstText(root, TIMESTAMP_KEYS);
        Instant parsed = parseInstant(text);

        if (parsed != null) {
            return new TimestampSource(text, "artifact", parsed);
        }

        Instant modified = fileModifiedInstant(path);

        if (modified != null) {
            return new TimestampSource(
                    text == null ? modified.toString() : text,
                    "file-system",
                    modified
            );
        }

        return new TimestampSource(text, "none", null);
    }

    private Instant parseInstant(String text) {

        if (text == null || text.isBlank()) {
            return null;
        }

        String value = text.trim();

        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            /* Fall through to the other accepted shapes. */
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            /* Fall through to the other accepted shapes. */
        }

        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            /* Fall through to the other accepted shapes. */
        }

        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            /* The artifact did not carry a readable timestamp. */
        }

        log.debug("GenieX artifact timestamp '{}' could not be parsed", value);

        return null;
    }

    private Instant fileModifiedInstant(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException | SecurityException exception) {
            return null;
        }
    }

    private Long ageDays(Instant instant, Path path) {

        Instant reference = instant != null ? instant : fileModifiedInstant(path);

        if (reference == null) {
            return null;
        }

        long days = Duration.between(reference, Instant.now()).toDays();

        return days < 0 ? 0L : days;
    }

    private Path resolve(String configuredPath) {

        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }

        try {
            return Path.of(configuredPath.trim());
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    /**
     * Breadth-first search for the first non blank textual value under any of the
     * alias keys. Breadth first keeps the shallowest, and therefore most likely
     * authoritative, key in a nested benchmark document.
     */
    private String findFirstText(JsonNode root, String[] keys) {

        if (root == null) {
            return null;
        }

        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.add(root);
        int visited = 0;

        while (!pending.isEmpty() && visited < MAX_VISITED_NODES) {

            JsonNode node = pending.poll();
            visited++;

            if (node.isObject()) {

                for (String key : keys) {
                    JsonNode candidate = node.get(key);
                    if (candidate != null && candidate.isValueNode()
                            && !candidate.asText().isBlank()) {
                        return candidate.asText().trim();
                    }
                }

                for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                    pending.add(it.next());
                }
            } else if (node.isArray()) {
                for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                    pending.add(it.next());
                }
            }
        }

        return null;
    }

    /**
     * Copies numeric benchmark leaves verbatim, keeping their dotted JSON path as
     * the key so nothing is renamed or reinterpreted.
     */
    private Map<String, Object> readStatistics(JsonNode root) {

        Map<String, Object> statistics = new LinkedHashMap<>();
        collectStatistics(root, "", statistics);

        return Map.copyOf(statistics);
    }

    private void collectStatistics(JsonNode node, String prefix, Map<String, Object> into) {

        if (node == null || into.size() >= MAX_STATISTICS) {
            return;
        }

        if (node.isObject()) {

            node.fields().forEachRemaining(entry -> {
                String key = prefix.isEmpty() ? entry.getKey() : prefix + '.' + entry.getKey();
                collectStatistics(entry.getValue(), key, into);
            });

            return;
        }

        if (node.isArray()) {

            for (int index = 0; index < node.size(); index++) {
                collectStatistics(node.get(index), prefix + '[' + index + ']', into);
            }

            return;
        }

        if (node.isNumber() && looksLikeMetric(prefix)) {
            into.put(prefix, node.numberValue());
        }
    }

    private boolean looksLikeMetric(String key) {

        String lower = key.toLowerCase(Locale.ROOT);

        for (String marker : STATISTIC_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }

        return false;
    }
}


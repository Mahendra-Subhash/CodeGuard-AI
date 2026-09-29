package com.codeguardai.ai.qualcomm;

import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Inspects the local machine for factual Qualcomm runtime evidence.
 *
 * Reported observations:
 *
 * - operating system, version and CPU architecture, as reported by the JVM
 * - the normalized architecture of the host
 * - Qualcomm/QNN/QAIRT/SNPE runtime components that actually exist, discovered
 *   through well known environment variables, system properties, library search
 *   paths and a bounded scan of those locations
 *
 * No QNN library is loaded, no JNI binding is created and no native code is
 * executed by this class. Absence of evidence is reported as absence, never as
 * availability.
 */
@Component
public class SystemQualcommRuntimeDetector implements QualcommRuntimeDetector {

    private static final String[] ROOT_ENVIRONMENT_VARIABLES = {
            "QNN_SDK_ROOT",
            "QAIRT_SDK_ROOT",
            "QNN_SDK",
            "SNPE_ROOT"
    };

    private static final String[] ROOT_SYSTEM_PROPERTIES = {
            "qnn.sdk.root",
            "qairt.sdk.root"
    };

    private static final String[] COMPONENT_NAME_MARKERS = {
            "qnn",
            "qairt",
            "snpe",
            "hexagon"
    };

    private static final String[] LIBRARY_EXTENSIONS = {
            ".dll",
            ".so",
            ".dylib",
            ".lib"
    };

    private static final int MAX_SCAN_DEPTH = 4;
    private static final int MAX_SCANNED_ENTRIES = 20000;

    @Override
    public QualcommHostFacts detect() {

        String operatingSystem = readProperty("os.name", "unknown");
        String osVersion = readProperty("os.version", "unknown");
        String cpuArchitecture = readProperty("os.arch", "unknown");
        String detectedArchitecture = normalizeArchitecture(cpuArchitecture);

        Set<String> detectedComponents = new LinkedHashSet<>();
        List<String> searchedLocations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int[] scannedEntries = {0};

        notes.add(
                "os.name=" + operatingSystem
                        + ", os.version=" + osVersion
                        + ", os.arch=" + cpuArchitecture
                        + " (normalized architecture " + detectedArchitecture + ")"
        );

        for (String variable : ROOT_ENVIRONMENT_VARIABLES) {
            searchedLocations.add("environment " + variable);

            String value = readEnvironment(variable);

            if (value != null && !value.isBlank()) {
                notes.add("Environment variable " + variable + " is set to " + value + ".");
                inspectLocation(value, detectedComponents, notes, scannedEntries);
            }
        }

        for (String property : ROOT_SYSTEM_PROPERTIES) {
            searchedLocations.add("system property " + property);

            String value = System.getProperty(property);

            if (value != null && !value.isBlank()) {
                notes.add("System property " + property + " is set to " + value + ".");
                inspectLocation(value, detectedComponents, notes, scannedEntries);
            }
        }

        searchedLocations.add("java.library.path");
        inspectSearchPath(System.getProperty("java.library.path"), detectedComponents, notes, scannedEntries);

        searchedLocations.add("environment PATH");
        inspectSearchPath(readEnvironment("PATH"), detectedComponents, notes, scannedEntries);

        searchedLocations.add("environment LD_LIBRARY_PATH");
        inspectSearchPath(readEnvironment("LD_LIBRARY_PATH"), detectedComponents, notes, scannedEntries);

        boolean componentsPresent = !detectedComponents.isEmpty();

        if (componentsPresent) {
            notes.add(
                    "Qualcomm runtime components were found at: "
                            + String.join(", ", detectedComponents) + "."
            );
        } else {
            notes.add(
                    "No QNN/QAIRT/SNPE runtime components were found on this machine,"
                            + " so no Qualcomm runtime can be loaded here."
            );
        }

        boolean arm64HostDetected =
                QualcommHostFacts.ARCHITECTURE_ARM64.equals(detectedArchitecture);

        if (!arm64HostDetected) {
            notes.add(
                    "Host architecture " + detectedArchitecture
                            + " is not ARM64, so Snapdragon Hexagon NPU execution cannot be verified on this machine."
            );
        }

        return new QualcommHostFacts(
                operatingSystem,
                osVersion,
                cpuArchitecture,
                detectedArchitecture,
                arm64HostDetected,
                componentsPresent,
                List.copyOf(detectedComponents),
                List.copyOf(searchedLocations),
                List.copyOf(notes)
        );
    }

    /**
     * Normalizes the raw {@code os.arch} value into a stable architecture name.
     */
    public static String normalizeArchitecture(String osArch) {

        if (osArch == null || osArch.isBlank()) {
            return QualcommHostFacts.ARCHITECTURE_UNKNOWN;
        }

        String value = osArch.trim().toLowerCase(Locale.ROOT);

        switch (value) {
            case "amd64", "x86_64", "x64" -> {
                return QualcommHostFacts.ARCHITECTURE_X86_64;
            }
            case "x86", "i386", "i486", "i586", "i686" -> {
                return QualcommHostFacts.ARCHITECTURE_X86_32;
            }
            case "aarch64", "arm64" -> {
                return QualcommHostFacts.ARCHITECTURE_ARM64;
            }
            case "arm", "armv6l", "armv7l" -> {
                return QualcommHostFacts.ARCHITECTURE_ARM32;
            }
            default -> {
                return value;
            }
        }
    }

    private String readProperty(String name, String fallback) {

        try {
            String value = System.getProperty(name);

            return value == null || value.isBlank()
                    ? fallback
                    : value;

        } catch (SecurityException exception) {
            return fallback;
        }
    }

    private String readEnvironment(String name) {

        try {
            return System.getenv(name);

        } catch (SecurityException exception) {
            return null;
        }
    }

    private void inspectLocation(
            String location,
            Set<String> detectedComponents,
            List<String> notes,
            int[] scannedEntries
    ) {

        try {
            Path path = Path.of(location.trim());

            if (Files.isRegularFile(path)) {

                if (isRuntimeComponentName(path.getFileName().toString())) {
                    detectedComponents.add(path.toAbsolutePath().toString());
                }

                return;
            }

            if (!Files.isDirectory(path)) {
                notes.add("Configured location " + location + " does not exist on this machine.");
                return;
            }

            int before = detectedComponents.size();

            scanDirectory(path, 0, detectedComponents, scannedEntries);

            if (detectedComponents.size() == before) {
                notes.add(
                        "Configured location " + location
                                + " exists but no Qualcomm runtime libraries were found under it."
                );
            }

        } catch (InvalidPathException | SecurityException exception) {
            notes.add("Configured location " + location + " could not be inspected.");
        }
    }

    private void inspectSearchPath(
            String pathList,
            Set<String> detectedComponents,
            List<String> notes,
            int[] scannedEntries
    ) {

        if (pathList == null || pathList.isBlank()) {
            return;
        }

        for (String entry : pathList.split(File.pathSeparator)) {

            if (entry == null || entry.isBlank()) {
                continue;
            }

            String trimmed = entry.trim();

            try {
                Path path = Path.of(trimmed);

                if (!Files.isDirectory(path)) {
                    continue;
                }

                int before = detectedComponents.size();

                try (Stream<Path> files = Files.list(path)) {

                    for (Path file : (Iterable<Path>) files::iterator) {

                        scannedEntries[0]++;

                        if (scannedEntries[0] > MAX_SCANNED_ENTRIES) {
                            return;
                        }

                        if (Files.isRegularFile(file)
                                && isRuntimeComponentName(file.getFileName().toString())) {

                            detectedComponents.add(file.toAbsolutePath().toString());
                        }
                    }
                }

                if (detectedComponents.size() > before) {
                    notes.add("Qualcomm runtime libraries were found on search path entry " + trimmed + ".");
                }

            } catch (InvalidPathException | SecurityException exception) {
                // Unreadable search path entries are skipped without any claim.
            } catch (Exception exception) {
                // Unreadable search path entries are skipped without any claim.
            }
        }
    }

    private void scanDirectory(
            Path directory,
            int depth,
            Set<String> detectedComponents,
            int[] scannedEntries
    ) {

        if (depth > MAX_SCAN_DEPTH || scannedEntries[0] > MAX_SCANNED_ENTRIES) {
            return;
        }

        try (Stream<Path> entries = Files.list(directory)) {

            for (Path entry : (Iterable<Path>) entries::iterator) {

                scannedEntries[0]++;

                if (scannedEntries[0] > MAX_SCANNED_ENTRIES) {
                    return;
                }

                if (Files.isDirectory(entry)) {
                    scanDirectory(entry, depth + 1, detectedComponents, scannedEntries);
                } else if (isRuntimeComponentName(entry.getFileName().toString())) {
                    detectedComponents.add(entry.toAbsolutePath().toString());
                }
            }

        } catch (Exception exception) {
            // Unreadable directories are skipped without any claim.
        }
    }

    private boolean isRuntimeComponentName(String fileName) {

        String lower = fileName.toLowerCase(Locale.ROOT);

        if (!containsComponentMarker(lower)) {
            return false;
        }

        for (String extension : LIBRARY_EXTENSIONS) {

            if (lower.endsWith(extension)) {
                return true;
            }
        }

        return false;
    }

    private boolean containsComponentMarker(String lowerCaseValue) {

        for (String marker : COMPONENT_NAME_MARKERS) {

            if (lowerCaseValue.contains(marker)) {
                return true;
            }
        }

        return false;
    }
}

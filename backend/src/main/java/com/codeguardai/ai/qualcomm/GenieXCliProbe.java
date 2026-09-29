package com.codeguardai.ai.qualcomm;

import com.codeguardai.config.QualcommProperties;
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

/**
 * Detects whether the GenieX launcher exists on this machine.
 *
 * <p>This is a file existence check only. No process is started and no
 * {@code geniex --version} style command is executed, because starting the
 * launcher can download a model, contact the network and block for a long time -
 * none of which belongs inside a status endpoint.
 *
 * <p>The result is deliberately informational. In a Qualcomm Device Cloud session
 * the launcher runs on the remote Snapdragon device while CodeGuard runs on the
 * workstation, so a missing local launcher neither proves nor disproves NPU
 * execution. The claim is decided by {@link QualcommVerificationService} from
 * server and artifact evidence.
 */
@Component
public class GenieXCliProbe {

    private static final String LAUNCHER_NAME = "geniex";

    /** Windows resolves the launcher as geniex.exe / .cmd / .bat / .ps1. */
    private static final String[] WINDOWS_EXTENSIONS = {".exe", ".cmd", ".bat", ""};

    private final QualcommProperties qualcommProperties;

    public GenieXCliProbe(QualcommProperties qualcommProperties) {
        this.qualcommProperties = qualcommProperties;
    }

    /**
     * @param detected         true when a launcher was found
     * @param locations        launcher paths that were found
     * @param searchedLocations directories that were inspected
     */
    public record Result(
            boolean detected,
            List<String> locations,
            List<String> searchedLocations
    ) {
    }

    public Result probe() {

        Set<String> found = new LinkedHashSet<>();
        List<String> searched = new ArrayList<>();
        QualcommProperties.Cli cli = qualcommProperties.getCli();

        String configured = cli.getExecutable();

        if (configured != null && !configured.isBlank()) {
            searched.add("configured codeguard.qualcomm.cli.executable");
            if (isReadableFile(configured)) {
                found.add(new File(configured.trim()).getAbsolutePath());
            }
        }

        for (String directory : cli.getExtraSearchPaths()) {
            if (directory == null || directory.isBlank()) {
                continue;
            }
            String trimmed = directory.trim();
            searched.add("configured path " + trimmed);
            addLaunchersIn(trimmed, found);
        }

        String pathVariable = readPathVariable();

        if (pathVariable != null && !pathVariable.isBlank()) {
            for (String entry : pathVariable.split(File.pathSeparator)) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                searched.add("PATH entry " + trimmed);
                addLaunchersIn(trimmed, found);
            }
        } else {
            searched.add("environment PATH (not available)");
        }

        return new Result(
                !found.isEmpty(),
                List.copyOf(found),
                List.copyOf(searched)
        );
    }

    private void addLaunchersIn(String directory, Set<String> found) {

        if (directory == null || directory.isBlank()) {
            return;
        }

        for (String candidate : candidateNames()) {
            Path candidatePath = resolve(directory, candidate);
            if (candidatePath != null && isReadableFile(candidatePath.toString())) {
                try {
                    found.add(candidatePath.toAbsolutePath().normalize().toString());
                } catch (Exception exception) {
                    /* Unreadable paths are skipped without any claim. */
                }
            }
        }
    }

    private List<String> candidateNames() {

        List<String> names = new ArrayList<>();

        if (isWindows()) {
            for (String extension : WINDOWS_EXTENSIONS) {
                names.add(LAUNCHER_NAME + extension);
            }
        } else {
            names.add(LAUNCHER_NAME);
        }

        return names;
    }

    private boolean isWindows() {
        return readSystemProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private Path resolve(String directory, String candidate) {
        try {
            return Path.of(directory, candidate);
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private boolean isReadableFile(String candidate) {
        try {
            return Files.isRegularFile(Path.of(candidate.trim())) && Files.isReadable(Path.of(candidate.trim()));
        } catch (InvalidPathException | SecurityException exception) {
            return false;
        }
    }

    private String readPathVariable() {
        return System.getenv("PATH");
    }

    private String readSystemProperty(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}

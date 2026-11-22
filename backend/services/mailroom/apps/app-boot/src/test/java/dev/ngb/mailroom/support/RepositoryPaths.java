package dev.ngb.mailroom.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root and the mailroom build root from the working directory Gradle gives a test. A test
 * never counts {@code ..} segments, so moving a module between directories cannot silently point a scan at the wrong
 * place or make it find nothing.
 */
public final class RepositoryPaths {

    private static final Path BUILD_ROOT = locateBuildRoot();
    private static final Path ROOT = locateRoot();

    private RepositoryPaths() {}

    /** The repository root: the nearest ancestor of the build root holding {@code contracts}. */
    public static Path repositoryRoot() {
        return ROOT;
    }

    /** The mailroom build root, which holds {@code settings.gradle.kts} and every module directory. */
    public static Path mailroomRoot() {
        return BUILD_ROOT;
    }

    /** The Gradle build root: the nearest ancestor holding {@code settings.gradle.kts}. */
    private static Path locateBuildRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Gradle build root not found above " + Path.of("").toAbsolutePath());
    }

    /** The repository root: the nearest ancestor of the build root holding {@code contracts}. */
    private static Path locateRoot() {
        Path dir = BUILD_ROOT;
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("contracts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found above " + BUILD_ROOT);
    }
}

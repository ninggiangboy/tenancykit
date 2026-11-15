package dev.ngb.attribution.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root and the attribution build root from the working directory Gradle gives a test. A test
 * never counts {@code ..} segments, so moving a module between directories cannot silently point a scan at the wrong
 * place or make it find nothing.
 */
public final class RepositoryPaths {

    private static final Path ROOT = locateRoot();

    private RepositoryPaths() {}

    /** The repository root: the nearest ancestor holding both {@code contracts} and {@code attribution}. */
    public static Path repositoryRoot() {
        return ROOT;
    }

    /** The attribution build root, which holds {@code settings.gradle.kts} and every module directory. */
    public static Path attributionRoot() {
        Path attribution = ROOT.resolve("attribution");
        if (!Files.isDirectory(attribution)) {
            throw new IllegalStateException("attribution directory not found under " + ROOT);
        }
        return attribution;
    }

    private static Path locateRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("contracts")) && Files.isDirectory(dir.resolve("attribution"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found above " + Path.of("").toAbsolutePath());
    }
}

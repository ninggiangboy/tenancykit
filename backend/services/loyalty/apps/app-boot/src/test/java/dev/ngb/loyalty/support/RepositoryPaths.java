package dev.ngb.loyalty.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root and the loyalty build root from the working directory Gradle gives a test. A test
 * never counts {@code ..} segments, so moving a module between directories cannot silently point a scan at the wrong
 * place or make it find nothing.
 */
public final class RepositoryPaths {

    private static final Path ROOT = locateRoot();

    private RepositoryPaths() {}

    /** The repository root: the nearest ancestor holding both {@code contracts} and {@code loyalty}. */
    public static Path repositoryRoot() {
        return ROOT;
    }

    /** The loyalty build root, which holds {@code settings.gradle.kts} and every module directory. */
    public static Path loyaltyRoot() {
        Path loyalty = ROOT.resolve("loyalty");
        if (!Files.isDirectory(loyalty)) {
            throw new IllegalStateException("loyalty directory not found under " + ROOT);
        }
        return loyalty;
    }

    private static Path locateRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("contracts")) && Files.isDirectory(dir.resolve("loyalty"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found above " + Path.of("").toAbsolutePath());
    }
}

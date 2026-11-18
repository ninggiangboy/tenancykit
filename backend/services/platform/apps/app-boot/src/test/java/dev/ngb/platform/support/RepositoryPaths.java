package dev.ngb.platform.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root and the platform build root from the working directory Gradle gives a test. A test
 * never counts {@code ..} segments, so moving a module cannot silently point a scan at the wrong place.
 */
public final class RepositoryPaths {

    private static final Path ROOT = locateRoot();

    private RepositoryPaths() {}

    /** The repository root: the nearest ancestor holding both {@code contracts} and {@code platform}. */
    public static Path repositoryRoot() {
        return ROOT;
    }

    /** The platform build root, which holds {@code settings.gradle.kts} and every module directory. */
    public static Path platformRoot() {
        Path platform = ROOT.resolve("platform");
        if (!Files.isDirectory(platform)) {
            throw new IllegalStateException("platform directory not found under " + ROOT);
        }
        return platform;
    }

    private static Path locateRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("contracts")) && Files.isDirectory(dir.resolve("platform"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found above " + Path.of("").toAbsolutePath());
    }
}

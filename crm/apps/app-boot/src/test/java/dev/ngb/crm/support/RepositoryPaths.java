package dev.ngb.crm.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root and the crm build root from the working directory Gradle gives a test. A test never
 * counts {@code ..} segments, so moving a module between directories cannot silently point a scan at the wrong place
 * or make it find nothing.
 */
public final class RepositoryPaths {

    private static final Path ROOT = locateRoot();

    private RepositoryPaths() {}

    /** The repository root: the nearest ancestor holding both {@code contracts} and {@code crm}. */
    public static Path repositoryRoot() {
        return ROOT;
    }

    /** The crm build root, which holds {@code settings.gradle.kts} and every module directory. */
    public static Path crmRoot() {
        Path crm = ROOT.resolve("crm");
        if (!Files.isDirectory(crm)) {
            throw new IllegalStateException("the crm directory was not found under " + ROOT);
        }
        return crm;
    }

    private static Path locateRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("contracts")) && Files.isDirectory(dir.resolve("crm"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("the repository root was not found above " + Path.of("").toAbsolutePath());
    }
}

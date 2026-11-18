package dev.ngb.customer.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root and the customer build root from the working directory Gradle gives a test. A test never
 * counts {@code ..} segments, so moving a module between directories cannot silently point a scan at the wrong place
 * or make it find nothing.
 */
public final class RepositoryPaths {

    private static final Path ROOT = locateRoot();

    private RepositoryPaths() {}

    /** The repository root: the nearest ancestor holding both {@code contracts} and {@code customer}. */
    public static Path repositoryRoot() {
        return ROOT;
    }

    /** The customer build root, which holds {@code settings.gradle.kts} and every module directory. */
    public static Path crmRoot() {
        Path customer = ROOT.resolve("customer");
        if (!Files.isDirectory(customer)) {
            throw new IllegalStateException("the customer directory was not found under " + ROOT);
        }
        return customer;
    }

    private static Path locateRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("contracts")) && Files.isDirectory(dir.resolve("customer"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("the repository root was not found above " + Path.of("").toAbsolutePath());
    }
}

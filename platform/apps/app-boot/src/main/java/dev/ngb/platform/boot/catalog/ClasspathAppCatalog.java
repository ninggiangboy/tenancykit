package dev.ngb.platform.boot.catalog;

import dev.ngb.platform.tenancy.application.entitlement.port.AppCatalog;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The suite's apps are the permission catalogs under {@code contracts/permissions}, packaged on the classpath under
 * {@code permissions/}: the file {@code billing.yaml} names the app {@code billing}.
 */
public class ClasspathAppCatalog implements AppCatalog {

    private static final String SUFFIX = ".yaml";

    private final Set<String> codes;

    public ClasspathAppCatalog() {
        Set<String> found = new TreeSet<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:permissions/*" + SUFFIX)) {
                String name = resource.getFilename();
                if (name != null && name.endsWith(SUFFIX)) {
                    found.add(name.substring(0, name.length() - SUFFIX.length()));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read the permission catalogs", e);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no permission catalogs were found on the classpath under permissions/");
        }
        this.codes = Set.copyOf(found);
    }

    @Override
    public Set<String> appCodes() {
        return codes;
    }
}

package dev.ngb.loyalty.webhook.adapter.endpoint.schema;

import dev.ngb.loyalty.webhook.application.endpoint.port.EventTypeCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The event types that exist are the schemas the build packages: each {@code contracts/events/loyalty/<type>.schema.json}
 * lands on the classpath as {@code events/<type>.schema.json}, so the catalog cannot drift from the contract and a new
 * schema needs no code change. A subscription to a type the app does not send is refused at creation instead of silently
 * never firing.
 */
public final class ClasspathEventTypeCatalog implements EventTypeCatalog {

    private static final String LOCATION = "classpath*:events/*.schema.json";
    private static final Pattern SUFFIX = Pattern.compile("\\.schema\\.json$");

    private final Set<String> eventTypes;

    public ClasspathEventTypeCatalog() {
        this.eventTypes = read();
    }

    @Override
    public Set<String> eventTypes() {
        return eventTypes;
    }

    private static Set<String> read() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
            Set<String> types = new TreeSet<>();
            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name != null) {
                    types.add(SUFFIX.matcher(name).replaceAll(""));
                }
            }
            if (types.isEmpty()) {
                throw new IllegalStateException("no event schema was found on the classpath: " + LOCATION);
            }
            return Set.copyOf(types);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the event schemas " + LOCATION, e);
        }
    }
}

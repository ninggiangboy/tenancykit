package dev.ngb.attribution.links.application.settings.command;

import dev.ngb.attribution.links.application.settings.port.SettingsWriter;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Writes one tenant's settings, validating the click parameter and the allowed origins.
 *
 * <p>An origin must be an absolute {@code https} origin (or {@code http://localhost} for development), because a
 * collection endpoint that accepts a wildcard would let any page post another tenant's visitor's events. A wildcard, a
 * path or a bare host is refused rather than normalized, so a caller learns the rule instead of a surprise later.
 */
public class UpdateSettingsUseCase {

    private static final Pattern CLICK_PARAM = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,31}$");

    /** The settings a caller may change; a null member is left as it is. */
    public record Change(String clickParam, List<String> allowedOrigins, Integer defaultWindowDays) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final SettingsWriter writer;
    private final Clock clock;

    /**
     * The write side. What the caller reads back is the read side's job: the endpoint writes and then reloads, so the
     * command never depends on a read model.
     */
    public UpdateSettingsUseCase(
            UnitOfWork unitOfWork, TenantContext tenantContext, SettingsWriter writer, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.writer = writer;
        this.clock = clock;
    }

    /** @return nothing: the caller reloads through the read side, so the command owns no read model */
    public void execute(Change change) {
        if (change == null) {
            throw new BadRequestException("A settings change is required.");
        }
        String clickParam = change.clickParam() == null ? null : change.clickParam().strip();
        if (clickParam != null && !CLICK_PARAM.matcher(clickParam).matches()) {
            throw new BadRequestException(
                    "A click parameter is 1 to 32 characters, starts with a letter and continues with letters, digits or"
                            + " '_'.");
        }
        List<String> origins = change.allowedOrigins() == null ? null : validOrigins(change.allowedOrigins());
        Integer window = change.defaultWindowDays();
        if (window != null && (window < 1 || window > 90)) {
            throw new BadRequestException("The default window is between 1 and 90 days.");
        }
        unitOfWork.transactional(() -> {
            writer.save(tenantContext.requireTenant().value(), clickParam, origins, window, clock.now());
            return null;
        });
    }

    private static List<String> validOrigins(List<String> raw) {
        List<String> origins = new ArrayList<>(raw.size());
        for (String value : raw) {
            origins.add(validOrigin(value));
        }
        return origins;
    }

    /**
     * One origin, normalized to scheme://host[:port] with no path and no trailing slash.
     *
     * @throws BadRequestException when the value is not an absolute https origin (or http on localhost)
     */
    static String validOrigin(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("An allowed origin must not be blank.");
        }
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (URISyntaxException e) {
            throw new BadRequestException("An allowed origin is not a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new BadRequestException("An allowed origin needs a host.");
        }
        if (host.contains("*")) {
            throw new BadRequestException("A wildcard origin is refused; name each origin explicitly.");
        }
        boolean localhost = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (!scheme.equals("https") && !(scheme.equals("http") && localhost)) {
            throw new BadRequestException(
                    "An allowed origin is https, or http://localhost for development; a wildcard or a plain host is"
                            + " refused.");
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) {
            throw new BadRequestException("An allowed origin carries no path; name the scheme, host and port only.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new BadRequestException("An allowed origin carries no query and no fragment.");
        }
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return scheme + "://" + host + port;
    }
}

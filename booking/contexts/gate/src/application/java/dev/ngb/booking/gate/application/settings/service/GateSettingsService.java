package dev.ngb.booking.gate.application.settings.service;

import dev.ngb.booking.gate.application.settings.port.GateSettingsRepository;
import dev.ngb.core.runtime.UnitOfWork;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Reads and changes the gate settings. An origin is a scheme and host with an optional port — never a path, a wildcard
 * or a credential — because the CORS filter echoes it verbatim and a wildcard would open the edge to every site.
 */
public class GateSettingsService {

    private static final int MAX_ORIGINS = 20;
    private static final int DEFAULT_TTL = 900;
    private static final int MAX_TTL = 3600;

    private final UnitOfWork unitOfWork;
    private final GateSettingsRepository settings;
    private final int tokenTtlCapSeconds;

    public GateSettingsService(
            UnitOfWork unitOfWork, GateSettingsRepository settings, int tokenTtlCapSeconds) {
        this.unitOfWork = unitOfWork;
        this.settings = settings;
        this.tokenTtlCapSeconds = tokenTtlCapSeconds;
    }

    public GateSettings get() {
        return unitOfWork.transactional(() -> settings.find().orElseGet(GateSettingsService::defaults));
    }

    public GateSettings update(List<String> origins, Integer maxTokenTtlSeconds) {
        List<String> normalized = origins == null ? List.of() : origins.stream().map(GateSettingsService::origin).toList();
        if (normalized.size() > MAX_ORIGINS) {
            throw new IllegalArgumentException("at most " + MAX_ORIGINS + " origins may be registered");
        }
        int ttl = maxTokenTtlSeconds == null
                ? Math.min(DEFAULT_TTL, tokenTtlCapSeconds)
                : maxTokenTtlSeconds;
        if (ttl < 1 || ttl > Math.min(MAX_TTL, tokenTtlCapSeconds)) {
            throw new IllegalArgumentException("maxTokenTtlSeconds must be between 1 and the app's token cap");
        }
        return unitOfWork.transactional(() -> {
            settings.save(normalized, ttl);
            return new GateSettings(normalized, ttl);
        });
    }

    /** @return the origin in the form the browser sends, or a refusal when it is not one */
    static String origin(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("an allowed origin must not be blank");
        }
        String text = raw.strip();
        if (text.equals("*") || text.contains("*")) {
            throw new IllegalArgumentException("a wildcard origin is not allowed");
        }
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                throw new IllegalArgumentException("an allowed origin must be http or https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("an allowed origin needs a host");
            }
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) {
                throw new IllegalArgumentException("an allowed origin must not carry a path");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("an allowed origin must not carry credentials, a query or a fragment");
            }
            return scheme + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("an allowed origin is not a valid URI");
        }
    }

    private static GateSettings defaults() {
        return new GateSettings(List.of(), DEFAULT_TTL);
    }
}

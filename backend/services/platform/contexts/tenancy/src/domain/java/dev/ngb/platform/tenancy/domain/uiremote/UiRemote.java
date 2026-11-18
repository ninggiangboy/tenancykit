package dev.ngb.platform.tenancy.domain.uiremote;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One loadable UI of the console: which app it belongs to, which shells may show it, and which registered version is
 * the one they get.
 *
 * <p>Two states are deliberate. Registering a version never enables anything and never activates it, so a CI deploy
 * cannot reach a browser on its own; and a remote cannot be enabled without an active version, so the shell never
 * receives one it could not mount. Only {@link #update} moves either.
 */
public final class UiRemote {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{1,30}$");

    private final String name;
    private final String appCode;
    private final Instant createdAt;
    private String title;
    private int navOrder;
    private EnumSet<RemoteAudience> audiences;
    private boolean enabled;
    private String activeVersion;
    private long version;

    private UiRemote(
            String name, String appCode, String title, int navOrder, EnumSet<RemoteAudience> audiences, boolean enabled,
            String activeVersion, Instant createdAt, long version) {
        this.name = requireName(name);
        this.appCode = requireAppCode(appCode);
        this.title = requireTitle(title);
        this.navOrder = requireNavOrder(navOrder);
        this.audiences = requireAudiences(audiences);
        this.enabled = enabled;
        this.activeVersion = activeVersion;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    /** A remote CI registers for the first time: it exists, but no shell may load it yet. */
    public static UiRemote register(
            String name, String appCode, String title, Set<RemoteAudience> audiences, int navOrder, Instant now) {
        return new UiRemote(name, appCode, title, navOrder, toSet(audiences), false, null, now, 1);
    }

    /** Rebuilds a stored remote; persistence adapters use this, business code does not. */
    public static UiRemote restore(
            String name, String appCode, String title, int navOrder, Set<RemoteAudience> audiences, boolean enabled,
            String activeVersion, Instant createdAt, long version) {
        return new UiRemote(name, appCode, title, navOrder, toSet(audiences), enabled, activeVersion, createdAt, version);
    }

    /**
     * Applies an operator's change. A field the caller did not name keeps its value; the active version moves only to a
     * version the caller confirmed is registered.
     *
     * @return false when nothing changed, so no audit entry is written for a no-op
     */
    public boolean update(Boolean enable, String targetVersion, String newTitle, Integer newNavOrder) {
        boolean changed = false;
        if (targetVersion != null && !targetVersion.equals(activeVersion)) {
            activeVersion = targetVersion;
            changed = true;
        }
        if (newTitle != null) {
            String normalized = requireTitle(newTitle);
            if (!normalized.equals(title)) {
                title = normalized;
                changed = true;
            }
        }
        if (newNavOrder != null) {
            int normalized = requireNavOrder(newNavOrder);
            if (normalized != navOrder) {
                navOrder = normalized;
                changed = true;
            }
        }
        if (enable != null && enable != enabled) {
            if (enable && activeVersion == null) {
                throw new IllegalArgumentException("a remote needs an active version before it can be enabled");
            }
            enabled = enable;
            changed = true;
        }
        if (changed) {
            version++;
        }
        return changed;
    }

    public String name() {
        return name;
    }

    public String appCode() {
        return appCode;
    }

    public String title() {
        return title;
    }

    public int navOrder() {
        return navOrder;
    }

    public Set<RemoteAudience> audiences() {
        return Collections.unmodifiableSet(audiences);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** @return the version a shell gets, or null when an operator has not activated one yet */
    public String activeVersion() {
        return activeVersion;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    private static String requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a remote name is 2 to 31 lower-case letters, digits or dashes, starting with a letter");
        }
        return name;
    }

    private static String requireAppCode(String appCode) {
        Objects.requireNonNull(appCode, "appCode");
        if (appCode.isBlank()) {
            throw new IllegalArgumentException("a remote names the app it belongs to");
        }
        return appCode;
    }

    private static String requireTitle(String title) {
        Objects.requireNonNull(title, "title");
        String trimmed = title.strip();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new IllegalArgumentException("a remote title is 1 to 100 characters");
        }
        return trimmed;
    }

    private static int requireNavOrder(int navOrder) {
        if (navOrder < 0) {
            throw new IllegalArgumentException("a navigation order is not negative");
        }
        return navOrder;
    }

    private static EnumSet<RemoteAudience> requireAudiences(EnumSet<RemoteAudience> audiences) {
        if (audiences == null || audiences.isEmpty()) {
            throw new IllegalArgumentException("a remote is mounted in at least one audience");
        }
        return audiences;
    }

    private static EnumSet<RemoteAudience> toSet(Set<RemoteAudience> audiences) {
        Objects.requireNonNull(audiences, "audiences");
        return audiences.isEmpty() ? EnumSet.noneOf(RemoteAudience.class) : EnumSet.copyOf(audiences);
    }
}

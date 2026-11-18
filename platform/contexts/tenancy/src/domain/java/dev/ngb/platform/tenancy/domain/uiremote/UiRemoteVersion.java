package dev.ngb.platform.tenancy.domain.uiremote;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One immutable build of a UI remote, registered by CI. Nothing ever updates it: the shell verifies the file at
 * {@code entryPath} against {@code integrity}, so a version that could be rewritten would make that check worthless.
 *
 * <p>{@code entryPath} is same-origin and inside the published directory of exactly this name and version. That is the
 * allow-list: a registry row can point the browser at nothing else, whatever a caller sends.
 */
public final class UiRemoteVersion {

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$");
    private static final Pattern ENTRY_FILE = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern INTEGRITY = Pattern.compile("^sha384-[A-Za-z0-9+/]{64}$");
    private static final String ENTRY_ROOT = "/console/remotes/";

    private final String remoteName;
    private final String version;
    private final String entryPath;
    private final String integrity;
    private final int sdkMajor;
    private final Instant registeredAt;
    private final String registeredBy;

    private UiRemoteVersion(
            String remoteName, String version, String entryPath, String integrity, int sdkMajor, Instant registeredAt,
            String registeredBy) {
        this.remoteName = remoteName;
        this.version = version;
        this.entryPath = entryPath;
        this.integrity = integrity;
        this.sdkMajor = sdkMajor;
        this.registeredAt = registeredAt;
        this.registeredBy = registeredBy;
    }

    public static UiRemoteVersion register(
            String remoteName, String version, String entryPath, String integrity, int sdkMajor, Instant now,
            String registeredBy) {
        requireVersion(version);
        Objects.requireNonNull(remoteName, "remoteName");
        Objects.requireNonNull(entryPath, "entryPath");
        requireEntryPath(remoteName, version, entryPath);
        if (integrity == null || !INTEGRITY.matcher(integrity).matches()) {
            throw new IllegalArgumentException("integrity is sha384- followed by the base64 of the manifest digest");
        }
        if (sdkMajor < 1) {
            throw new IllegalArgumentException("sdkMajor starts at 1");
        }
        if (registeredBy == null || registeredBy.isBlank()) {
            throw new IllegalArgumentException("a registered version names who registered it");
        }
        return new UiRemoteVersion(remoteName, version, entryPath, integrity, sdkMajor, Objects.requireNonNull(now, "now"), registeredBy);
    }

    /** Rebuilds a stored version; persistence adapters use this, business code does not. */
    public static UiRemoteVersion restore(
            String remoteName, String version, String entryPath, String integrity, int sdkMajor, Instant registeredAt,
            String registeredBy) {
        return new UiRemoteVersion(remoteName, version, entryPath, integrity, sdkMajor, registeredAt, registeredBy);
    }

    /** @return true when registering again describes the same build, which is the idempotent case */
    public boolean sameContent(UiRemoteVersion other) {
        return entryPath.equals(other.entryPath) && integrity.equals(other.integrity) && sdkMajor == other.sdkMajor;
    }

    public String remoteName() {
        return remoteName;
    }

    public String version() {
        return version;
    }

    public String entryPath() {
        return entryPath;
    }

    public String integrity() {
        return integrity;
    }

    public int sdkMajor() {
        return sdkMajor;
    }

    public Instant registeredAt() {
        return registeredAt;
    }

    public String registeredBy() {
        return registeredBy;
    }

    private static void requireVersion(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "a version is 1 to 64 letters, digits, dots, plus signs, underscores or dashes, starting with a letter or digit");
        }
    }

    private static void requireEntryPath(String remoteName, String version, String entryPath) {
        String prefix = ENTRY_ROOT + remoteName + "/" + version + "/";
        if (!entryPath.startsWith(prefix)) {
            throw new IllegalArgumentException("the entry path must start with " + prefix);
        }
        String file = entryPath.substring(prefix.length());
        if (!ENTRY_FILE.matcher(file).matches() || file.equals(".") || file.equals("..")) {
            throw new IllegalArgumentException("the entry path must name a file directly under " + prefix);
        }
    }
}

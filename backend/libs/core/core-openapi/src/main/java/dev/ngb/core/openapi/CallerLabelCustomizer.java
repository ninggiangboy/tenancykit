package dev.ngb.core.openapi;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.RequiresPermission;
import io.swagger.v3.oas.models.Operation;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * Derives every operation's caller label from the annotation that declares it, checks a permission-coded endpoint
 * against the catalog, and writes both into the document.
 *
 * <p>The sources, in order: {@link Callers} on the method, {@link Callers} on the class, {@link PublicEndpoint}, then
 * an annotation that implies a caller (a Billing portal permission means {@link Caller#CUSTOMER_SESSION}). The path is
 * never read. A handler that resolves to none fails the contract generation, and an architecture rule reports it
 * earlier.
 *
 * <p>{@code x-allowed-callers} is the machine-readable label. Scalar does not render {@code x-} extensions, so the
 * same customizer appends one generated line to the operation's description —
 * {@code Callers: tenant-user. Requires `billing:wallet:read`.} — which is where a reader sees it.
 *
 * <p>A permission-coded operation's declared callers must be allowed by the catalog. When a code applies in both
 * scopes, a controller may narrow its route to one of them (Platform's users and roles are split into an
 * administrator controller and a tenant controller); the declared set may then be a non-empty subset. Every other
 * code must match exactly, so an over- or under-declared caller fails the build.
 */
public final class CallerLabelCustomizer implements OperationCustomizer {

    /** The machine-readable label. */
    public static final String X_ALLOWED_CALLERS = "x-allowed-callers";

    /** The permission the endpoint enforces, kept here so one customizer owns both. */
    public static final String X_REQUIRED_PERMISSION = "x-required-permission";

    private final CallerCatalog catalog;

    /** Annotations that imply a caller without restating it; the key is matched on the handler method. */
    private final Map<Class<? extends Annotation>, Caller> implied;

    public CallerLabelCustomizer(CallerCatalog catalog) {
        this(catalog, Map.of());
    }

    public CallerLabelCustomizer(CallerCatalog catalog, Map<Class<? extends Annotation>, Caller> implied) {
        this.catalog = catalog;
        this.implied = Map.copyOf(implied);
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        Set<Caller> callers = resolve(handlerMethod);
        RequiresPermission required = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequiresPermission.class);
        String code = required == null ? null : required.value();
        if (code != null) {
            operation.addExtension(X_REQUIRED_PERMISSION, code);
            checkCatalog(handlerMethod, code, callers);
        }
        List<String> labels = ordered(callers).stream().map(Caller::wire).toList();
        operation.addExtension(X_ALLOWED_CALLERS, labels);
        appendDescription(operation, line(callers, code));
        return operation;
    }

    /**
     * The callers of one handler, resolved from the one source that declares them. Public so a contract test can
     * compare a hand-written document with the derivation.
     */
    public Set<Caller> resolve(HandlerMethod handlerMethod) {
        Callers onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), Callers.class);
        if (onMethod != null) {
            return checked(onMethod.value(), handlerMethod, "@Callers on the method");
        }
        Callers onClass = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), Callers.class);
        if (onClass != null) {
            return checked(onClass.value(), handlerMethod, "@Callers on the class");
        }
        PublicEndpoint open = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PublicEndpoint.class);
        if (open != null) {
            return Set.of(open.caller());
        }
        for (Map.Entry<Class<? extends Annotation>, Caller> entry : implied.entrySet()) {
            if (handlerMethod.hasMethodAnnotation(entry.getKey())) {
                return Set.of(entry.getValue());
            }
        }
        throw new IllegalStateException("No caller is declared for " + handlerMethod
                + "; put @Callers on the method or class, or mark it @PublicEndpoint.");
    }

    private static Set<Caller> checked(Caller[] declared, HandlerMethod handlerMethod, String source) {
        if (declared.length == 0) {
            throw new IllegalStateException(source + " names no caller on " + handlerMethod);
        }
        EnumSet<Caller> callers = EnumSet.noneOf(Caller.class);
        Collections.addAll(callers, declared);
        if (callers.size() != declared.length) {
            throw new IllegalStateException(source + " repeats a caller on " + handlerMethod);
        }
        return callers;
    }

    private void checkCatalog(HandlerMethod handlerMethod, String code, Set<Caller> declared) {
        if (!catalog.catalogues(code)) {
            throw new IllegalStateException(handlerMethod + " requires " + code + ", which is in no permission catalog");
        }
        Set<Caller> allowed = EnumSet.noneOf(Caller.class);
        Set<PermissionScope> scopes = catalog.scopes(code);
        if (scopes.contains(PermissionScope.TENANT)) {
            allowed.add(Caller.TENANT_USER);
        }
        if (scopes.contains(PermissionScope.PLATFORM)) {
            allowed.add(Caller.PLATFORM_USER);
        }
        if (catalog.grantableToTenantSystem(code)) {
            allowed.add(Caller.TENANT_SYSTEM);
        }
        if (scopes.isEmpty()) {
            allowed.add(Caller.INTERNAL);
        }
        if (!allowed.containsAll(declared)) {
            Set<Caller> extras = EnumSet.copyOf(declared);
            extras.removeAll(allowed);
            throw new IllegalStateException(handlerMethod + " declares " + ordered(extras) + " for " + code
                    + ", but the catalog allows only " + ordered(allowed));
        }
        // A code valid in both scopes may be split into an administrator route and a tenant route, each declaring one
        // caller. Every other code allows exactly its catalogued callers, in both directions.
        boolean splitByScope = scopes.contains(PermissionScope.PLATFORM) && scopes.contains(PermissionScope.TENANT);
        if (!splitByScope && !declared.equals(allowed)) {
            throw new IllegalStateException(handlerMethod + " declares " + ordered(declared) + " for " + code
                    + ", but the catalog allows exactly " + ordered(allowed));
        }
    }

    private static void appendDescription(Operation operation, String line) {
        String existing = operation.getDescription();
        operation.setDescription(existing == null || existing.isBlank()
                ? line
                : existing.stripTrailing() + "\n\n" + line);
    }

    /** The generated line every operation carries, so a reader of the rendered reference sees who may call it. */
    public static String line(Set<Caller> callers, String permissionCode) {
        String who = String.join(", ", ordered(callers).stream().map(Caller::wire).toList());
        return permissionCode == null
                ? "Callers: " + who + "."
                : "Callers: " + who + ". Requires `" + permissionCode + "`.";
    }

    /** Declaration order, so the label and the description are stable across builds. */
    private static List<Caller> ordered(Set<Caller> callers) {
        return Arrays.stream(Caller.values()).filter(callers::contains).toList();
    }
}

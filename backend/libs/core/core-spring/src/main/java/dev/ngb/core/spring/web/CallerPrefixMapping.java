package dev.ngb.core.spring.web;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.CallerPrefixes;
import dev.ngb.core.security.SelfService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Mounts every handler under the prefixes its declared callers imply, and removes the un-prefixed mapping the framework
 * registered, because a controller declares only its resource path and the caller is the one place its prefix can come
 * from. Leaving the bare path reachable would serve a route outside every prefix-scoped guard.
 *
 * <p>A controller that still spells a derived prefix itself — the form the rollout removes one app at a time — is left
 * exactly as the framework registered it, so a half-moved app boots and keeps working. That is what makes
 * {@code /system/v1/...} appear for a route whose twin is still spelled {@code /api/v1/...}.
 *
 * <p>The callers come from {@link CallerResolver}, the same reading the OpenAPI label uses. A handler that resolves to
 * none is left alone; the architecture rule and the contract test report it. A standard path declared with
 * {@code prefix = NONE} keeps the path it declares.
 *
 * <p>It runs once, after the framework has registered the initial mappings, and only in a servlet runtime.
 */
public class CallerPrefixMapping implements InitializingBean {

    private final RequestMappingHandlerMapping mapping;
    private final String appCode;
    private final boolean publicNamespace;
    private final CallerResolver resolver;

    /**
     * @param mapping the framework's {@code requestMappingHandlerMapping}, the one that serves {@code @RestController}
     * @param appCode this app's code, the app segment of its public namespace
     * @param publicNamespace whether the app's public controllers already live under {@code /public/<app code>}
     * @param resolver the one reading of a handler's callers
     */
    public CallerPrefixMapping(
            RequestMappingHandlerMapping mapping, String appCode, boolean publicNamespace, CallerResolver resolver) {
        this.mapping = mapping;
        this.appCode = appCode;
        this.publicNamespace = publicNamespace;
        this.resolver = resolver;
    }

    /** Convenience for a service whose callers come from the annotations alone. */
    public CallerPrefixMapping(RequestMappingHandlerMapping mapping, String appCode, boolean publicNamespace) {
        this(mapping, appCode, publicNamespace, new CallerResolver(Map.of()));
    }

    @Override
    public void afterPropertiesSet() {
        // A snapshot per handler: unregistering must not change the collection this loop walks.
        Map<HandlerMethod, List<RequestMappingInfo>> byHandler = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            byHandler.computeIfAbsent(entry.getValue(), handler -> new ArrayList<>()).add(entry.getKey());
        }
        byHandler.forEach(this::remount);
    }

    private void remount(HandlerMethod handler, List<RequestMappingInfo> registered) {
        if (isInfrastructure(handler)) {
            return;
        }
        List<String> prefixes = prefixesOf(handler);
        if (prefixes.isEmpty()) {
            return;
        }
        if (registered.stream().allMatch(info -> isUnderAny(info, prefixes))) {
            // A half-moved controller: it already spells the prefix, so the framework's mapping is the one to keep.
            return;
        }
        for (RequestMappingInfo info : registered) {
            mapping.unregisterMapping(info);
        }
        for (RequestMappingInfo info : registered) {
            for (String prefix : prefixes) {
                mapping.registerMapping(mount(info, prefix), handler.getBean(), handler.getMethod());
            }
        }
    }

    /** The prefixes this handler's declared callers imply, or none when it declares none or keeps its path. */
    private List<String> prefixesOf(HandlerMethod handler) {
        if (CallerResolver.keepsThePath(handler)) {
            return List.of();
        }
        if (handler.hasMethodAnnotation(SelfService.class)) {
            return List.of(CallerPrefixes.ME);
        }
        Set<Caller> callers = resolver.resolve(handler);
        return callers.isEmpty() ? List.of() : CallerPrefixes.of(callers, appCode, publicNamespace);
    }

    private static boolean isUnderAny(RequestMappingInfo info, List<String> prefixes) {
        for (String path : info.getPatternValues()) {
            for (String prefix : prefixes) {
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One mapping per prefix: the declared paths, each moved under the prefix, with every other condition kept. */
    private static RequestMappingInfo mount(RequestMappingInfo info, String prefix) {
        List<String> paths = new ArrayList<>();
        for (String path : info.getPatternValues()) {
            paths.add(prefixed(prefix, path));
        }
        return info.mutate().paths(paths.toArray(String[]::new)).build();
    }

    private static String prefixed(String prefix, String path) {
        return path.isBlank() || path.equals("/") ? prefix : prefix + path;
    }

    /** springdoc and the actuators register their own mappings, which declare no caller. */
    private static boolean isInfrastructure(HandlerMethod handler) {
        String type = handler.getBeanType().getName();
        return type.startsWith("org.springdoc.") || type.startsWith("org.springframework.");
    }
}

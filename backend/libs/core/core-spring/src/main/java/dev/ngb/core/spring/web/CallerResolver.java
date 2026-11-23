package dev.ngb.core.spring.web;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.core.security.SelfService;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * The one place a handler's declared callers are read, so the OpenAPI label, the route prefix and the architecture rule
 * cannot disagree. The sources, in order: {@link Callers} on the method, {@link Callers} on the class,
 * {@link SelfService}, {@link PublicEndpoint}, then an annotation that implies a caller (a portal permission means
 * {@link Caller#CUSTOMER_SESSION}). The path is never read.
 *
 * <p>The implied annotations are supplied by the service, so the core never names one.
 */
public final class CallerResolver {

    private final Map<Class<? extends Annotation>, Caller> implied;

    /** @param implied annotations that imply a caller without restating it, matched on the handler method */
    public CallerResolver(Map<Class<? extends Annotation>, Caller> implied) {
        this.implied = Map.copyOf(implied);
    }

    /** @return the declared callers; empty when the handler declares none */
    public Set<Caller> resolve(HandlerMethod handlerMethod) {
        Callers onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), Callers.class);
        if (onMethod != null) {
            return declared(onMethod.value(), handlerMethod, "@Callers on the method");
        }
        Callers onClass = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), Callers.class);
        if (onClass != null) {
            return declared(onClass.value(), handlerMethod, "@Callers on the class");
        }
        if (handlerMethod.hasMethodAnnotation(SelfService.class)) {
            return EnumSet.of(Caller.PLATFORM_USER, Caller.TENANT_USER);
        }
        PublicEndpoint open = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PublicEndpoint.class);
        if (open != null) {
            return EnumSet.of(open.caller());
        }
        for (Map.Entry<Class<? extends Annotation>, Caller> entry : implied.entrySet()) {
            if (handlerMethod.hasMethodAnnotation(entry.getKey())) {
                return EnumSet.of(entry.getValue());
            }
        }
        return EnumSet.noneOf(Caller.class);
    }

    /** Whether the handler keeps the path it declares because the path is a standard ({@code prefix = NONE}). */
    public static boolean keepsThePath(HandlerMethod handlerMethod) {
        Callers onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), Callers.class);
        if (onMethod != null) {
            return onMethod.prefix() == Callers.Prefix.NONE;
        }
        Callers onClass = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), Callers.class);
        return onClass != null && onClass.prefix() == Callers.Prefix.NONE;
    }

    private static Set<Caller> declared(Caller[] declared, HandlerMethod handlerMethod, String source) {
        if (declared.length == 0) {
            throw new IllegalStateException(source + " names no caller on " + handlerMethod);
        }
        EnumSet<Caller> callers = EnumSet.noneOf(Caller.class);
        Arrays.stream(declared).forEach(callers::add);
        if (callers.size() != declared.length) {
            throw new IllegalStateException(source + " repeats a caller on " + handlerMethod);
        }
        return callers;
    }
}

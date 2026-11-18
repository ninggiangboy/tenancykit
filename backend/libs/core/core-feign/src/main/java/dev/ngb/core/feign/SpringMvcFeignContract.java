package dev.ngb.core.feign;

import feign.MethodMetadata;
import java.lang.reflect.Method;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

/**
 * Reads Spring MVC annotations, but keeps an encoded slash encoded: an id such as {@code a/b} must reach one path
 * segment, not a different path.
 */
public final class SpringMvcFeignContract extends SpringMvcContract {

    @Override
    public MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
        MethodMetadata metadata = super.parseAndValidateMetadata(targetType, method);
        metadata.template().decodeSlash(false);
        return metadata;
    }
}

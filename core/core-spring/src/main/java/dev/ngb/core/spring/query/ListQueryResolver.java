package dev.ngb.core.spring.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.QueryParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies the {@link ListQuery} parameter of a {@link Listing} method by parsing the query string against the schema
 * the annotation names. A request the schema or a limit refuses surfaces as a {@code BadRequestException}, which the
 * shared exception handlers answer with a 400.
 */
public class ListQueryResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ListQuery.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container, NativeWebRequest request,
            WebDataBinderFactory binderFactory) {
        Listing listing = parameter.getMethodAnnotation(Listing.class);
        if (listing == null) {
            throw new IllegalStateException("a ListQuery parameter needs @Listing on its method: " + parameter.getMethod());
        }
        Map<String, List<String>> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> params.put(name, List.of(values)));
        return QueryParser.parse(ListingSchemas.of(listing.value()), params, Set.of(listing.passthrough()));
    }
}

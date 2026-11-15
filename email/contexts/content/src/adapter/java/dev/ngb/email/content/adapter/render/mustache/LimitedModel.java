package dev.ngb.email.content.adapter.render.mustache;

import dev.ngb.email.content.api.render.MissingTemplateVariableException;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The model the sandbox hands to JMustache.
 *
 * <p>JMustache renders a missing variable as an empty string, which is exactly the half-filled email that must never
 * be sent. The model therefore refuses every missing lookup, at the root, at every nested {@code Map} and on every
 * element a loop yields: {@code receiver.first_name} is as strict as {@code first_name}, and an element of
 * {@code {{#items}}} is wrapped again before the template reads its fields. Sections and inverted sections over a
 * missing name fail the same way.
 *
 * <p>It also bounds iteration: an {@link Iterable} or an array is wrapped in a counter that charges every element to
 * the render's total, so a loop over a large or nested collection cannot outrun the budget.
 */
final class LimitedModel extends AbstractMap<String, Object> {

    private final Map<String, Object> variables;
    private final RenderBudget budget;

    LimitedModel(Map<String, Object> variables, RenderBudget budget) {
        this.variables = variables;
        this.budget = budget;
    }

    @Override
    public Object get(Object key) {
        return lookup(String.valueOf(key));
    }

    @Override
    public boolean containsKey(Object key) {
        // JMustache asks whether the whole dotted name exists before it splits it; resolving the path here keeps a
        // missing leaf strict without failing on a name that is merely compound.
        lookup(String.valueOf(key));
        return true;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return variables.entrySet();
    }

    /** Resolves {@code a.b.c} segment by segment and refuses the whole path when any segment is missing. */
    private Object lookup(String path) {
        budget.checkClock();
        Object current = variables;
        for (String segment : path.split("\\.", -1)) {
            Map<String, Object> level = asMap(current);
            if (level == null || !level.containsKey(segment)) {
                throw new MissingTemplateVariableException(path);
            }
            current = level.get(segment);
        }
        return wrap(current);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof LimitedModel model) {
            return model.variables;
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private Object wrap(Object value) {
        budget.checkClock();
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) map;
            return new LimitedModel(nested, budget);
        }
        if (value instanceof Object[] array) {
            return new CountingIterable(Arrays.asList(array));
        }
        if (value instanceof Iterable<?> iterable) {
            return new CountingIterable(iterable);
        }
        return value;
    }

    /** Charges every element and wraps it, so a loop's elements are as strict as the root model. */
    private final class CountingIterable implements Iterable<Object> {

        private final Iterable<?> delegate;

        private CountingIterable(Iterable<?> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Iterator<Object> iterator() {
            Iterator<?> iterator = delegate.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Object next() {
                    budget.iteration();
                    return wrap(iterator.next());
                }
            };
        }
    }
}

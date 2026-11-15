package dev.ngb.notification.dispatch.adapter.notification.render;

import dev.ngb.notification.dispatch.application.notification.exception.MissingTemplateVariableException;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

/**
 * A model that refuses an unknown variable.
 *
 * <p>JMustache renders a missing variable as an empty string, which is exactly the half-filled email that must never
 * be sent. The fix is not a flag: JMustache resolves values through the model's {@code Map}, so a decorator whose
 * {@code get} throws turns a missing variable into a hard failure. Extra keys in the delegate are harmless.
 */
final class StrictTemplateModel extends AbstractMap<String, Object> {

    private final Map<String, Object> variables;

    StrictTemplateModel(Map<String, Object> variables) {
        this.variables = Map.copyOf(variables);
    }

    @Override
    public Object get(Object key) {
        if (!variables.containsKey(key)) {
            throw new MissingTemplateVariableException(String.valueOf(key));
        }
        return variables.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        if (!variables.containsKey(key)) {
            throw new MissingTemplateVariableException(String.valueOf(key));
        }
        return true;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return variables.entrySet();
    }
}

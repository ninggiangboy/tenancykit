package dev.ngb.core.query;

import java.util.List;
import java.util.function.Function;

/**
 * Every listing answers with this one shape, so a client declares a single type per resource. {@code page} is null
 * unless the caller sent {@code page_info=true}.
 */
public record Page<T>(List<T> data, PageInfo page) {

    public Page {
        data = List.copyOf(data);
    }

    /** @return the same page with each row converted, paging facts unchanged */
    public <R> Page<R> map(Function<? super T, ? extends R> mapper) {
        return new Page<>(data.stream().<R>map(mapper).toList(), page);
    }
}

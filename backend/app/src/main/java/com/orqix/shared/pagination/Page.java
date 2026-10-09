package com.orqix.shared.pagination;

import java.util.List;
import java.util.function.Function;

/** Keyset page helper: callers fetch {@code limit + 1} rows and let this decide the next cursor. */
public final class Page {

    private Page() {
    }

    public record Result<T>(List<T> items, String nextCursor) {
    }

    public static <T> Result<T> of(List<T> rowsPlusOne, int limit, Function<T, CursorCodec.Position> position,
            CursorCodec codec, String filterKey) {
        if (rowsPlusOne.size() <= limit) {
            return new Result<>(rowsPlusOne, null);
        }
        List<T> items = rowsPlusOne.subList(0, limit);
        return new Result<>(List.copyOf(items), codec.encode(position.apply(items.get(limit - 1)), filterKey));
    }
}

package com.imin.iminapi.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Runs an {@code IN (:ids)} query in chunks, so an audience of any size stays under Postgres's 65,535
 * bind-parameter limit per statement.
 */
public final class IdChunks {

    /** Ids bound per statement; leaves room for the query's other parameters. */
    public static final int MAX_IDS_PER_QUERY = 10_000;

    private IdChunks() {}

    public static <T, R> List<R> query(Collection<T> ids, Function<Collection<T>, List<R>> query) {
        return query(ids, MAX_IDS_PER_QUERY, query);
    }

    static <T, R> List<R> query(Collection<T> ids, int chunkSize, Function<Collection<T>, List<R>> query) {
        if (ids.size() <= chunkSize) return query.apply(ids);
        // A duplicate split across two chunks would return its row twice; one IN list returned it once.
        List<T> distinct = ids.stream().distinct().toList();
        List<R> out = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += chunkSize) {
            out.addAll(query.apply(distinct.subList(from, Math.min(from + chunkSize, distinct.size()))));
        }
        return out;
    }
}

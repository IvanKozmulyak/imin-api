package com.imin.iminapi.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdChunksTest {

    @Test
    void atOrUnderTheLimit_passesTheCollectionThroughInOneQuery() {
        List<List<Integer>> calls = new ArrayList<>();
        List<Integer> ids = List.of(1, 2, 2, 3);

        List<Integer> out = IdChunks.query(ids, 4, chunk -> { calls.add(List.copyOf(chunk)); return List.copyOf(chunk); });

        assertThat(calls).containsExactly(List.of(1, 2, 2, 3));
        assertThat(out).containsExactly(1, 2, 2, 3);
    }

    @Test
    void overTheLimit_queriesEachDistinctIdOnceInBoundedChunksAndConcatenatesInOrder() {
        List<List<Integer>> calls = new ArrayList<>();
        // 1 recurs in what would be the last chunk: the IN query it replaces returned its row once.
        List<Integer> ids = List.of(1, 2, 3, 4, 5, 1);

        List<Integer> out = IdChunks.query(ids, 2, chunk -> { calls.add(List.copyOf(chunk)); return List.copyOf(chunk); });

        assertThat(calls).containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));
        assertThat(out).containsExactly(1, 2, 3, 4, 5);
    }
}

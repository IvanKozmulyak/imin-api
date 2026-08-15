package com.imin.iminapi.push;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Expo payload contract and the batch boundary.
 *
 * <p>Expo accepts at most 100 messages per request. A fan-out that silently
 * sends only the first 100 is the exact failure this covers — a sold-out
 * headliner is precisely when the list is long and precisely when nobody would
 * notice the tail was dropped.
 */
class DropAlertPushTest {

    @Test
    void batchesOfMoreThanOneHundredAreSplit() {
        List<PushMessage> messages = java.util.stream.IntStream.range(0, 250)
                .mapToObj(i -> new PushMessage("ExponentPushToken[t" + i + "]",
                        "Tickets are live", "Vechirka", PushMessage.CHANNEL_DROP_ALERTS,
                        Map.of("eventId", "e1")))
                .toList();

        List<List<PushMessage>> batches = ExpoPushSender.batch(messages);

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).hasSize(100);
        assertThat(batches.get(2)).hasSize(50);
        assertThat(batches.stream().mapToInt(List::size).sum()).isEqualTo(250);
    }

    @Test
    void emptyInputProducesNoBatches() {
        assertThat(ExpoPushSender.batch(List.of())).isEmpty();
    }
}

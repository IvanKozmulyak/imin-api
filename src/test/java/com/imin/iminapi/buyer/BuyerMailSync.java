package com.imin.iminapi.buyer;

import com.imin.iminapi.email.RecordingEmailService;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Waits for the buyer account emails to actually be sent.
 *
 * <p>{@code BuyerMailListener} sends AFTER_COMMIT and {@code @Async} on
 * {@code ticketEmailExecutor}, so by the time a MockMvc call returns the send
 * is queued, not done. Every assertion about what was (or was not) mailed has
 * to drain the pool first — otherwise a "the code was mailed" test is a race
 * and a "nothing was mailed" test is vacuous.
 *
 * <p>The task is always submitted on the request thread, inside the commit, so
 * once the call has returned the work is in the queue or on a worker: draining
 * to idle is sufficient, no sleep-and-hope needed.
 */
final class BuyerMailSync {

    private static final long TIMEOUT_MILLIS = 10_000;
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    private BuyerMailSync() {}

    static void drain(Executor executor) {
        if (!(executor instanceof ThreadPoolTaskExecutor pool)) return;
        ThreadPoolExecutor tpe = pool.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        int idle = 0;
        while (System.currentTimeMillis() < deadline) {
            // Twice in a row: a worker that has taken a task off the queue but
            // not yet marked itself active leaves a one-instant idle-looking gap.
            idle = (tpe.getQueue().isEmpty() && tpe.getActiveCount() == 0) ? idle + 1 : 0;
            if (idle == 2) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("buyer mail executor still busy after " + TIMEOUT_MILLIS + "ms");
    }

    /** Drains, then the six digits of the newest mail to {@code to}; fails when there is none. */
    static String codeTo(RecordingEmailService mail, Executor executor, String to) {
        drain(executor);
        List<RecordingEmailService.SentEmail> sent = mail.sent();
        for (int i = sent.size() - 1; i >= 0; i--) {
            RecordingEmailService.SentEmail m = sent.get(i);
            if (!to.equalsIgnoreCase(m.to())) continue;
            Matcher digits = SIX_DIGITS.matcher(m.text() == null ? m.html() : m.text());
            if (!digits.find()) throw new AssertionError("newest mail to " + to + " carries no six-digit code: " + m.subject());
            return digits.group(1);
        }
        throw new AssertionError("no mail sent to " + to);
    }

    /** Drains, then every mail sent to {@code to}, oldest first; never a global read, async sends land late. */
    static List<RecordingEmailService.SentEmail> sentTo(RecordingEmailService mail, Executor executor, String to) {
        drain(executor);
        return mail.sent().stream().filter(m -> to.equalsIgnoreCase(m.to())).toList();
    }
}

// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.plugin;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How the engines call a plugin: in parallel, under a timeout, and never letting one failure reach
 * another pair.
 *
 * <p>It lives in one place because the two loops make the <em>same</em> decision rather than two
 * similar ones. ADR-0012 and ADR-0026 say so outright — {@code HealthResult} carries "the same three
 * values, same meanings, same reason for existing" as {@code DiscoveryResult} — and the reason is
 * shared too: a plugin that throws must be recorded as a failure rather than propagating, because
 * both ADRs made the value explicit precisely so a failed poll is never mistaken for an empty one,
 * and ADR-0046 makes that value leave the store untouched.
 *
 * <p>Two copies of this would be two copies of an exception cascade whose branches are each a
 * decision: cancel on timeout, re-set the interrupt flag, and turn everything else into a recorded
 * cause. Those drift silently — the divergence shows up as one loop swallowing a failure the other
 * reports, which reads as a plugin being flaky.
 *
 * <p>The domain differences stay with each engine, where they belong: which roster it builds, what
 * it hands the plugin, what it records, and which fold it runs afterwards.
 */
public final class PluginCalls {

    private static final Logger log = LoggerFactory.getLogger(PluginCalls.class);

    /** Userinfo ends at the first {@code @} before any {@code /}, {@code ?} or {@code #}. */
    private static final Pattern USERINFO = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\\s?#@]+@");

    private PluginCalls() {}

    /**
     * Runs every pair on its own virtual thread and waits for all of them.
     *
     * <p><b>One failing pair does not touch the others.</b> A dead staging cluster must not stall
     * production, and under ADR-0004 a stalled environment renders as drift — a legitimate reading
     * of the data, and therefore the hardest class of bug this system can produce.
     */
    public static void inParallel(List<Runnable> calls) {
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = calls.stream().map(workers::submit).toList();
            running.forEach(PluginCalls::awaitQuietly);
        }
    }

    /**
     * Calls one plugin under an engine-imposed timeout, mapping every way it can fail to the
     * caller's own failure value.
     *
     * @param failed builds the caller's result type from a cause, so the engine records a failure
     *     rather than this class knowing what a result looks like
     */
    public static <R> R within(Duration timeout, String what, Callable<R> call, Function<String, R> failed) {
        try (ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<R> running = worker.submit(call);
            try {
                return running.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Cancelled, because the timeout exists to stop a hung plugin overlapping its own
                // next run (ADR-0103) — leaving it running would defeat that within two cadences.
                running.cancel(true);
                return failed.apply(what + " timed out after " + timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failed.apply(what + " was interrupted");
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                return failed.apply("%s threw %s: %s"
                        .formatted(what, cause.getClass().getSimpleName(), withoutUserinfo(cause.getMessage())));
            }
        }
    }

    /**
     * A reason is served by the API and shown in the shell, and a client that quotes the URL it
     * could not reach quotes whatever userinfo an operator wrote into it. The host and path are
     * what diagnose the failure, so they stay and {@code user:password@} does not.
     */
    static String withoutUserinfo(String message) {
        return message == null ? null : USERINFO.matcher(message).replaceAll("$1****@");
    }

    private static void awaitQuietly(Future<?> task) {
        try {
            task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Everything a plugin can throw was already turned into a recorded outcome by `within`.
            // Reaching here means the engine's own recording failed, which is ours and not theirs.
            log.error("a poll failed outside the plugin call", e);
        }
    }
}

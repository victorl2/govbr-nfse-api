package br.com.nfse.http;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounds how much CPU-bound work runs at once.
 *
 * <p>Virtual threads make it free to *accept* thousands of requests, which is
 * exactly the problem for DANFSe rendering: each in-flight render holds a PDF
 * document graph in memory, so admitting all of them converts a load spike into
 * memory pressure. Benchmarking the shipped image showed the shape of that
 * failure — at 16 concurrent renders a 96 MiB container shed more than half its
 * requests, and a 64 MiB one was OOM-killed.
 *
 * <p>Queueing is the right response, not parallelism: the work is CPU-bound, so
 * beyond one render per core extra concurrency buys nothing anyway. Callers that
 * cannot be admitted within the timeout get {@link Overloaded}, which the API
 * maps to <strong>529 Service Overloaded</strong> — an honest "come back
 * shortly" instead of a slow death, and distinguishable from the 503 that means
 * the service cannot issue notes at all.
 */
public final class ConcurrencyGate {

    private final Semaphore permits;
    private final Duration queueTimeout;
    private final int capacity;

    public ConcurrencyGate(int capacity, Duration queueTimeout) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
        }
        this.capacity = capacity;
        // Fair: under sustained overload an unfair semaphore can starve a waiter
        // indefinitely, which would turn into a spurious 503 for one unlucky caller.
        this.permits = new Semaphore(capacity, true);
        this.queueTimeout = queueTimeout;
    }

    public int capacity() {
        return capacity;
    }

    /** Runs {@code work} once a permit is free, or throws {@link Overloaded}. */
    public <T> T call(Callable<T> work) throws Exception {
        if (!permits.tryAcquire(queueTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new Overloaded("too many concurrent renders (limit " + capacity
                    + "); the request waited " + queueTimeout.toMillis() + " ms for a slot");
        }
        try {
            return work.call();
        } finally {
            permits.release();
        }
    }

    /** Signals that the caller should retry shortly; the API answers 529. */
    public static class Overloaded extends RuntimeException {
        public Overloaded(String message) {
            super(message);
        }
    }
}

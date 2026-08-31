package br.com.nfse.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rendering a DANFSe is CPU-bound and allocates heavily, so admitting unbounded
 * concurrent renders converts a load spike into memory pressure. Measured on the
 * shipped image: 16 concurrent renders in a 96 MiB box shed more than half the
 * requests. The gate turns that into queueing, and then into an honest 503.
 */
class ConcurrencyGateTest {

    @Test
    void runsWorkAndReturnsItsResult() throws Exception {
        ConcurrencyGate gate = new ConcurrencyGate(2, Duration.ofSeconds(1));
        assertEquals("rendered", gate.call(() -> "rendered"));
    }

    @Test
    void neverAdmitsMoreThanThePermitCount() throws Exception {
        int permits = 3;
        ConcurrencyGate gate = new ConcurrencyGate(permits, Duration.ofSeconds(5));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger highWater = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(12);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    gate.call(() -> {
                        highWater.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        Thread.sleep(20);
                        inFlight.decrementAndGet();
                        return null;
                    });
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    done.countDown();
                }
            }));
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "work did not finish");
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(highWater.get() <= permits,
                "gate admitted " + highWater.get() + " concurrent renders, permit count is " + permits);
    }

    @Test
    void rejectsWithOverloadedOnceTheQueueTimeoutElapses() throws Exception {
        ConcurrencyGate gate = new ConcurrencyGate(1, Duration.ofMillis(50));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                gate.call(() -> {
                    holding.countDown();
                    release.await();
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertTrue(holding.await(5, TimeUnit.SECONDS));
        assertThrows(ConcurrencyGate.Overloaded.class, () -> gate.call(() -> "should not run"));
        release.countDown();
        holder.join();
    }

    /** A failed render must not leak its permit, or the gate closes for good. */
    @Test
    void releasesThePermitWhenTheWorkThrows() {
        ConcurrencyGate gate = new ConcurrencyGate(1, Duration.ofMillis(50));
        assertThrows(IllegalStateException.class, () -> gate.call(() -> {
            throw new IllegalStateException("render blew up");
        }));
        assertThrows(IllegalStateException.class, () -> gate.call(() -> {
            throw new IllegalStateException("still admitted, so the permit came back");
        }));
    }
}

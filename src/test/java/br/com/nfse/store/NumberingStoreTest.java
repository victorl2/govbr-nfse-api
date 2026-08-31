package br.com.nfse.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DPS numbering is the one piece of state the service cannot reconstruct from
 * SEFIN cheaply: reusing a (série, número) is a duplicate emission, and skipping
 * blindly leaves gaps nobody can explain. So it is allocated here, durably, and
 * never twice.
 */
class NumberingStoreTest {

    @TempDir
    Path dir;

    @Test
    void allocatesSequentiallyFromOne() {
        NumberingStore store = new NumberingStore(dir);

        assertEquals(1, store.next("1"));
        assertEquals(2, store.next("1"));
        assertEquals(3, store.next("1"));
    }

    @Test
    void keepsSeriesIndependent() {
        NumberingStore store = new NumberingStore(dir);

        assertEquals(1, store.next("1"));
        assertEquals(1, store.next("2"));
        assertEquals(2, store.next("1"));
    }

    /** A restart must not hand out a number that was already used. */
    @Test
    void survivesARestart() {
        assertEquals(1, new NumberingStore(dir).next("1"));
        assertEquals(2, new NumberingStore(dir).next("1"));
        assertEquals(3, new NumberingStore(dir).next("1"));
    }

    @Test
    void neverIssuesTheSameNumberTwiceUnderConcurrency() throws Exception {
        NumberingStore store = new NumberingStore(dir);
        int callers = 64;
        ConcurrentLinkedQueue<Long> issued = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);

        for (int i = 0; i < callers; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    issued.add(store.next("1"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "allocation stalled");

        assertEquals(callers, issued.size());
        assertEquals(callers, Set.copyOf(issued).size(), "a number was issued twice: " + issued);
        assertEquals(callers, store.lastAllocated("1"));
    }

    /**
     * Nothing was signed or sent, so the number can go back — but only if it is
     * still the last one out, otherwise returning it would hand it to a second
     * caller while the first is mid-flight.
     */
    @Test
    void releaseRollsBackOnlyTheMostRecentAllocation() {
        NumberingStore store = new NumberingStore(dir);
        long first = store.next("1");
        long second = store.next("1");

        store.release("1", second);
        assertEquals(second, store.next("1"), "the released number should be reissued");

        store.release("1", first);   // no longer the highest — must be ignored
        assertEquals(3, store.next("1"));
    }

    @Test
    void seedingRaisesTheFloorSoAlreadyConsumedNumbersAreNotReissued() {
        NumberingStore store = new NumberingStore(dir);

        store.seed("1", 18);

        assertEquals(19, store.next("1"));
    }

    /** Lowering the counter would re-issue a número that already exists at SEFIN. */
    @Test
    void seedingRefusesToLowerTheCounter() {
        NumberingStore store = new NumberingStore(dir);
        store.seed("1", 18);

        assertThrows(IllegalArgumentException.class, () -> store.seed("1", 5));
        assertEquals(19, store.next("1"));
    }

    @Test
    void reportsEverySerieItHasIssued() {
        NumberingStore store = new NumberingStore(dir);
        store.next("1");
        store.next("1");
        store.next("7");

        assertEquals(List.of("1", "7"), store.series().stream().sorted().toList());
        assertEquals(2, store.lastAllocated("1"));
        assertEquals(1, store.lastAllocated("7"));
        assertEquals(0, store.lastAllocated("9"), "an untouched série starts at zero");
    }

    /** A série is a filename here, so anything path-like has to be refused. */
    @Test
    void rejectsASerieThatIsNotAValidTSSerie() {
        NumberingStore store = new NumberingStore(dir);

        assertThrows(IllegalArgumentException.class, () -> store.next("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.next("toolong6"));
        assertThrows(IllegalArgumentException.class, () -> store.next(""));
        assertThrows(IllegalArgumentException.class, () -> store.next(null));
    }
}

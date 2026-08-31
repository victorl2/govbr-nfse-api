package br.com.nfse.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Durable DPS numbering, one counter per série.
 *
 * <p>This is the piece of state the service cannot rebuild from SEFIN: the
 * número is chosen by the emitter, and a repeat is a duplicate emission while a
 * blind skip leaves a gap nobody can account for. Keeping it in the caller's
 * head — which is where it lived until now — makes an unattended restart or two
 * concurrent callers enough to collide.
 *
 * <p>One small file per série holding the last number allocated. Every mutation
 * takes an OS-level {@link FileLock} and is forced to disk before it is
 * reported, so a second process (a rolling deploy overlapping by a second) sees
 * the same counter rather than its own copy.
 */
public final class NumberingStore {

    /** TSSerie: up to five characters. Constrained here so a série cannot escape the directory. */
    private static final Pattern SERIE = Pattern.compile("[A-Za-z0-9]{1,5}");

    private final Path dir;

    public NumberingStore(Path baseDir) {
        this.dir = baseDir.resolve("numbering");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create the numbering directory " + dir, e);
        }
    }

    /** Allocates and durably records the next número for {@code serie}. */
    public synchronized long next(String serie) {
        return update(serie, current -> current + 1);
    }

    /**
     * Returns a número to the pool — but only when it is still the most recent
     * allocation. Anything else would hand a live number to a second caller, so
     * it is ignored and the série simply carries a gap.
     */
    public synchronized void release(String serie, long number) {
        update(serie, current -> current == number ? current - 1 : current);
    }

    /**
     * Raises the counter to {@code lastConsumed}, for a série whose earlier
     * numbers were issued before this store existed. Refuses to lower it: that
     * would re-issue a número SEFIN has already accepted.
     */
    public synchronized void seed(String serie, long lastConsumed) {
        if (lastConsumed < 0) {
            throw new IllegalArgumentException("lastConsumed must not be negative, was " + lastConsumed);
        }
        update(serie, current -> {
            if (lastConsumed < current) {
                throw new IllegalArgumentException("refusing to lower série " + serie + " from "
                        + current + " to " + lastConsumed + " — número " + (lastConsumed + 1)
                        + " onwards has already been issued and would be duplicated");
            }
            return lastConsumed;
        });
    }

    /** The last número allocated for {@code serie}, or 0 if none ever was. */
    public synchronized long lastAllocated(String serie) {
        Path file = file(serie);
        if (!Files.exists(file)) {
            return 0;
        }
        try (FileChannel channel = FileChannel.open(file, READ);
             FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {
            return read(channel);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the counter for série " + serie, e);
        }
    }

    /** Every série this store has issued a number for. */
    public synchronized List<String> series() {
        try (Stream<Path> files = Files.list(dir)) {
            List<String> found = new ArrayList<>();
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".seq"))
                    .map(n -> n.substring(0, n.length() - ".seq".length()))
                    .forEach(found::add);
            return found;
        } catch (IOException e) {
            throw new IllegalStateException("cannot list the numbering directory " + dir, e);
        }
    }

    private long update(String serie, java.util.function.LongUnaryOperator change) {
        Path file = file(serie);
        try (FileChannel channel = FileChannel.open(file, CREATE, READ, WRITE);
             FileLock lock = channel.lock()) {
            long updated = change.applyAsLong(read(channel));
            write(channel, updated);
            // The number must be on disk before the caller is told it owns it;
            // otherwise a crash re-issues it and SEFIN sees a duplicate.
            channel.force(true);
            return updated;
        } catch (IOException e) {
            throw new IllegalStateException("cannot update the counter for série " + serie, e);
        }
    }

    private static long read(FileChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(32);
        channel.position(0);
        int read = channel.read(buffer);
        if (read <= 0) {
            return 0;
        }
        String text = new String(buffer.array(), 0, read, StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? 0 : Long.parseLong(text);
    }

    private static void write(FileChannel channel, long value) throws IOException {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        channel.truncate(0);
        channel.position(0);
        channel.write(ByteBuffer.wrap(bytes));
    }

    private Path file(String serie) {
        if (serie == null || !SERIE.matcher(serie).matches()) {
            throw new IllegalArgumentException(
                    "série must be 1–5 alphanumeric characters, was '" + serie + "'");
        }
        return dir.resolve(serie + ".seq");
    }
}

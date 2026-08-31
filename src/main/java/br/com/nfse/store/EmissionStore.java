package br.com.nfse.store;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The immutable record of every note this service has issued.
 *
 * <p>A fiscal document has to outlive the HTTP response that carried it: Brazil
 * requires it kept for five years, and without a local copy the only way back to
 * a note whose answer was lost in transit is to walk the ADN feed. So each
 * attempt is written down before it is sent and updated when it resolves.
 *
 * <p>Plain files rather than a database, deliberately. The access pattern is
 * "look up one document by its identifier", the volume is a few thousand notes a
 * year, and a directory of XML is the format an auditor can actually read. It
 * also keeps the image at 76 MB and adds no dependency, no driver and no native
 * library to a container that has no package manager.
 *
 * <p>{@link #begin} is the concurrency guard. Creating the directory is atomic
 * on every POSIX filesystem, so two callers racing on the same dpsId — the
 * deterministic (CNPJ, série, número) identity — cannot both proceed.
 *
 * <p><strong>Single node.</strong> The atomicity relies on a local filesystem;
 * two instances sharing NFS would not be safe.
 */
public final class EmissionStore {

    /** Identifiers become path segments; both are fixed-alphabet by construction. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,120}");

    private final Path emissions;
    private final Path chaveIndex;
    private final ObjectMapper json;

    public EmissionStore(Path baseDir, ObjectMapper json) {
        this.emissions = baseDir.resolve("emissions");
        this.chaveIndex = baseDir.resolve("index").resolve("chave");
        this.json = json;
        try {
            Files.createDirectories(emissions);
            Files.createDirectories(chaveIndex);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create the emission store under " + baseDir, e);
        }
    }

    /**
     * Claims {@code dpsId} and stores the signed DPS, before anything is sent.
     *
     * @throws DuplicateEmission if this dpsId was already claimed — the caller
     *                           should read the existing record rather than emit again
     */
    public void begin(String dpsId, String serie, String number, String signedDpsXml, String at) {
        Path dir = dir(dpsId);
        try {
            Files.createDirectory(dir);
        } catch (FileAlreadyExistsException e) {
            throw new DuplicateEmission(dpsId);
        } catch (IOException e) {
            throw new IllegalStateException("cannot claim emission " + dpsId, e);
        }
        writeAtomically(dir.resolve("dps.xml"), signedDpsXml.getBytes(StandardCharsets.UTF_8));
        save(new EmissionRecord(dpsId, serie, number, EmissionRecord.Status.SUBMITTED,
                null, null, at, at));
    }

    /** Records the authorised note and makes it findable by access key. */
    public void authorized(String dpsId, String chaveAcesso, String nfseXml, String at) {
        if (nfseXml != null) {
            writeAtomically(dir(dpsId).resolve("nfse.xml"), nfseXml.getBytes(StandardCharsets.UTF_8));
        }
        EmissionRecord updated = require(dpsId)
                .withStatus(EmissionRecord.Status.AUTHORIZED, chaveAcesso, null, at);
        save(updated);
        // Written last: the index may only ever point at a record already on disk.
        writeAtomically(chaveFile(chaveAcesso), dpsId.getBytes(StandardCharsets.UTF_8));
    }

    /** Records a terminal outcome that produced no note. */
    public void finished(String dpsId, EmissionRecord.Status status, String detail, String at) {
        save(require(dpsId).withStatus(status, null, detail, at));
    }

    /**
     * Drops a claim for a submission that provably never reached SEFIN, so the
     * same (série, número) can be retried. Only safe when the absence of a note
     * has been confirmed — a claim is the only record that a número might be in
     * flight, and discarding one that did arrive would allow a duplicate.
     *
     * @throws IllegalStateException if the record is AUTHORIZED
     */
    public void discard(String dpsId) {
        byDpsId(dpsId).ifPresent(record -> {
            if (record.status() == EmissionRecord.Status.AUTHORIZED) {
                throw new IllegalStateException(
                        "refusing to discard authorised emission " + dpsId + " (" + record.chaveAcesso() + ")");
            }
        });
        Path dir = dir(dpsId);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (java.nio.file.NoSuchFileException e) {
            // nothing claimed; nothing to undo
        } catch (IOException e) {
            throw new IllegalStateException("cannot discard the claim for " + dpsId, e);
        }
    }

    public Optional<EmissionRecord> byDpsId(String dpsId) {
        Path file = dir(dpsId).resolve("record.json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(Files.readAllBytes(file), EmissionRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the record for " + dpsId, e);
        }
    }

    public Optional<EmissionRecord> byChave(String chaveAcesso) {
        Path pointer = chaveFile(chaveAcesso);
        if (!Files.exists(pointer)) {
            return Optional.empty();
        }
        try {
            return byDpsId(Files.readString(pointer, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            throw new IllegalStateException("cannot resolve access key " + chaveAcesso, e);
        }
    }

    public Optional<String> dpsXml(String dpsId) {
        return readIfPresent(dir(dpsId).resolve("dps.xml"));
    }

    public Optional<String> nfseXml(String dpsId) {
        return readIfPresent(dir(dpsId).resolve("nfse.xml"));
    }

    /** The most recently updated records first — an operator's "what just happened". */
    public List<EmissionRecord> recent(int limit) {
        try (Stream<Path> dirs = Files.list(emissions)) {
            List<EmissionRecord> records = new ArrayList<>();
            dirs.map(d -> d.getFileName().toString())
                    .map(this::byDpsId)
                    .flatMap(Optional::stream)
                    .forEach(records::add);
            records.sort(Comparator.comparing(EmissionRecord::updatedAt).reversed());
            return records.size() > limit ? records.subList(0, limit) : records;
        } catch (IOException e) {
            throw new IllegalStateException("cannot list emissions under " + emissions, e);
        }
    }

    private EmissionRecord require(String dpsId) {
        return byDpsId(dpsId).orElseThrow(() ->
                new IllegalStateException("no emission was begun for DPS id " + dpsId));
    }

    private void save(EmissionRecord record) {
        try {
            writeAtomically(dir(record.dpsId()).resolve("record.json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsBytes(record));
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialise the record for " + record.dpsId(), e);
        }
    }

    private Optional<String> readIfPresent(Path file) {
        try {
            return Files.exists(file)
                    ? Optional.of(Files.readString(file, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    /**
     * Writes through a temporary file and an atomic rename, so a crash mid-write
     * leaves the previous content rather than a truncated document.
     */
    private static void writeAtomically(Path target, byte[] content) {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temp, content);
            Files.move(temp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + target, e);
        }
    }

    private Path dir(String dpsId) {
        return emissions.resolve(safe(dpsId, "DPS id"));
    }

    private Path chaveFile(String chaveAcesso) {
        return chaveIndex.resolve(safe(chaveAcesso, "access key"));
    }

    private static String safe(String id, String what) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(what + " is not a valid identifier: '" + id + "'");
        }
        return id;
    }

    /** The dpsId was already claimed; read the existing record instead of emitting. */
    public static class DuplicateEmission extends RuntimeException {
        public DuplicateEmission(String dpsId) {
            super("an emission already exists for DPS id " + dpsId);
        }
    }
}

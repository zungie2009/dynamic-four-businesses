package com.fourbusiness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Generic, type-agnostic durable record store. Records are kept in memory and
 * mirrored to disk one file per record, at {@code <dataDir>/<kind>/<id>.json}.
 * A record is rewritten in full on every mutation and a deleted record's file
 * is removed from disk immediately, so "write data, restart the process, read
 * it back" holds: a fresh Store over the same directory reconstructs every
 * kind by walking its subdirectory.
 *
 * {@link #transact(Supplier)} gives callers a single synchronized section to
 * perform a read-check-then-write sequence atomically against everything else
 * touching this Store.
 */
public final class Store {
    private final Path root;
    private final Map<String, Map<String, Map<String,Object>>> byKind = new HashMap<>();
    private final Random random = new Random();

    public Store(Path root) {
        this.root = Objects.requireNonNull(root);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage root: " + root, e);
        }
        loadExisting();
    }

    private void loadExisting() {
        java.io.File[] kindDirs = root.toFile().listFiles(java.io.File::isDirectory);
        if (kindDirs == null) return;
        for (java.io.File kindDir : kindDirs) {
            String kind = kindDir.getName();
            Map<String,Map<String,Object>> records = new LinkedHashMap<>();
            java.io.File[] files = kindDir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) {
                for (java.io.File f : files) {
                    try {
                        String raw = Files.readString(f.toPath());
                        if (raw.isBlank()) continue;
                        Map<String,Object> record = Json.asObject(Json.parse(raw));
                        Object id = record.get("id");
                        if (id != null) records.put(String.valueOf(id), record);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Cannot read record file: " + f, e);
                    }
                }
            }
            byKind.put(kind, records);
        }
    }

    private Map<String,Map<String,Object>> bucket(String kind) {
        return byKind.computeIfAbsent(kind, k -> new LinkedHashMap<>());
    }

    /**
     * Generates an id per the application's declared identity strategy,
     * {@code DICD.IDENTITY.DEMO_RANDOM_6_DIGIT/1}: a random 6-digit decimal
     * string (leading zeros allowed, e.g. "042817"), unique within its kind.
     * Collisions are vanishingly rare (1-in-900000 per attempt against an
     * empty bucket) but are still checked and retried rather than assumed
     * away, so correctness never depends on that rarity.
     */
    private String newId(String kind) {
        Map<String,Map<String,Object>> existing = bucket(kind);
        String id;
        do {
            id = String.format("%06d", random.nextInt(1_000_000));
        } while (existing.containsKey(id));
        return id;
    }

    /** Runs an arbitrary read-check-write sequence atomically with respect to every other Store method. */
    public synchronized <T> T transact(Supplier<T> action) {
        return action.get();
    }

    public synchronized Map<String,Object> create(String kind, Map<String,Object> fields) {
        String id = newId(kind);
        Map<String,Object> record = new LinkedHashMap<>();
        record.put("id", id);
        if (fields != null) record.putAll(fields);
        String now = Instant.now().toString();
        record.put("createdAt", now);
        record.put("updatedAt", now);
        bucket(kind).put(id, record);
        writeToDisk(kind, id, record);
        return new LinkedHashMap<>(record);
    }

    public synchronized Map<String,Object> get(String kind, String id) {
        if (id == null) return null;
        Map<String,Object> r = bucket(kind).get(id);
        return r == null ? null : new LinkedHashMap<>(r);
    }

    public synchronized List<Map<String,Object>> all(String kind) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map<String,Object> r : bucket(kind).values()) out.add(new LinkedHashMap<>(r));
        return out;
    }

    /**
     * Upserts a record at a caller-chosen, fixed id (rather than a generated
     * one) - for singleton documents such as administration configuration,
     * where the id is a well-known name (e.g. "APPLICATION", "OWNER") rather
     * than an entity instance identity. Fully replaces any existing record at
     * that id; preserves {@code createdAt} across an update so callers can
     * tell "first written" from "last changed".
     */
    public synchronized Map<String,Object> put(String kind, String id, Map<String,Object> fields) {
        Objects.requireNonNull(id, "id");
        Map<String,Object> existing = bucket(kind).get(id);
        Map<String,Object> record = new LinkedHashMap<>();
        record.put("id", id);
        if (fields != null) record.putAll(fields);
        String now = Instant.now().toString();
        record.put("createdAt", existing != null && existing.get("createdAt") != null ? existing.get("createdAt") : now);
        record.put("updatedAt", now);
        bucket(kind).put(id, record);
        writeToDisk(kind, id, record);
        return new LinkedHashMap<>(record);
    }

    public synchronized Map<String,Object> update(String kind, String id, Map<String,Object> changes) {
        Map<String,Object> existing = bucket(kind).get(id);
        if (existing == null) return null;
        if (changes != null) existing.putAll(changes);
        existing.put("updatedAt", Instant.now().toString());
        writeToDisk(kind, id, existing);
        return new LinkedHashMap<>(existing);
    }

    public synchronized boolean remove(String kind, String id) {
        boolean existed = id != null && bucket(kind).remove(id) != null;
        if (existed) deleteFromDisk(kind, id);
        return existed;
    }

    private void writeToDisk(String kind, String id, Map<String,Object> record) {
        try {
            Path dir = root.resolve(kind);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(id + ".json"), Json.write(record));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write record: " + kind + "/" + id, e);
        }
    }

    private void deleteFromDisk(String kind, String id) {
        try {
            Files.deleteIfExists(root.resolve(kind).resolve(id + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete record: " + kind + "/" + id, e);
        }
    }

    /** The storage root, exposed so a capability that needs its own on-disk area (e.g. file blobs) can nest under it. */
    public Path root() { return root; }
}

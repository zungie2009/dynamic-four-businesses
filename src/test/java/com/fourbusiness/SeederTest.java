package com.fourbusiness;

import com.fourbusiness.BusinessModel.BusinessNeuron;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the seed data this build had to author itself (the spec mandates a deterministic
 * per-partition dataset but never supplies its content - see README). Confirms every seeded
 * REFERENCE resolves within its own business partition, seeding is idempotent (a marker
 * prevents a second pass from duplicating records), and Administration defaults are
 * initialized so {@code GET /administration} never has to invent a response.
 */
class SeederTest {

    @Test
    void seedingCreatesRecordsForEveryEntityInEveryBusinessAndReferencesResolve(@TempDir Path tempDir) {
        Store store = new Store(tempDir.resolve("storage"));
        List<String> auditedOperations = new ArrayList<>();
        Seeder.seedIfNeeded(store, (business, entityId, operation, target) -> auditedOperations.add(operation));

        for (BusinessNeuron business : Businesses.ALL) {
            for (var entity : business.entities()) {
                List<Map<String,Object>> records = store.all(business.storageKind(entity.id()));
                assertFalse(records.isEmpty(), business.userId() + "." + entity.id() + " should have at least one seeded record");
                for (var field : entity.fields()) {
                    if (field.type() != BusinessModel.FieldType.REFERENCE) continue;
                    String targetKind = business.storageKind(field.referenceEntity());
                    for (var record : records) {
                        Object refValue = record.get(field.id());
                        if (refValue == null) continue;
                        assertNotNull(store.get(targetKind, String.valueOf(refValue)),
                            business.userId() + "." + entity.id() + "." + field.id() + " -> " + refValue + " must resolve within the same partition");
                    }
                }
            }
        }
        assertTrue(auditedOperations.contains("SEED_RECORD_CREATED"));
        assertTrue(auditedOperations.contains("DEMO_SEED_COMPLETED"));
    }

    @Test
    void seedingIsAppliedAtMostOncePerPartition(@TempDir Path tempDir) {
        Store store = new Store(tempDir.resolve("storage"));
        Seeder.seedIfNeeded(store, (b, e, op, t) -> {});
        int firstPassCount = store.all(Businesses.byUserId(Businesses.ROBERT).storageKind("clients")).size();

        Seeder.seedIfNeeded(store, (b, e, op, t) -> {});
        int secondPassCount = store.all(Businesses.byUserId(Businesses.ROBERT).storageKind("clients")).size();

        assertEquals(firstPassCount, secondPassCount, "a second seed pass must not duplicate records");
        assertTrue(firstPassCount > 0);
    }

    @Test
    void seedingInitializesAdministrationDefaultsForEveryBusiness(@TempDir Path tempDir) {
        Store store = new Store(tempDir.resolve("storage"));
        Seeder.seedIfNeeded(store, (b, e, op, t) -> {});

        for (BusinessNeuron business : Businesses.ALL) {
            Map<String,Object> settings = store.get(business.administrationKind(), "SETTINGS");
            assertNotNull(settings, business.userId());
            assertEquals(business.businessName(), settings.get("businessName"));
            assertEquals(business.defaultTheme(), settings.get("theme"));
            assertEquals(Boolean.TRUE, settings.get("notifications"));
            assertEquals(Seeder.SCHEMA_VERSION, settings.get("schemaVersion"));
        }
    }
}

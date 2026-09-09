package com.fourbusiness;

import com.fourbusiness.BusinessModel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-level coverage of {@link RecordValidator} against the real declared schemas in
 * {@link Businesses} (loaded from {@code /schema/businesses.json} - see that class's javadoc).
 * Exercises every {@link BusinessModel.FieldType}, the workflow-governed initial-state and
 * direct-edit-blocking behavior, an ungoverned enum (Sofia's Propiedad.Estado), REFERENCE
 * resolution scoped to one business's own partition, and delete-restrict.
 */
class RecordValidatorTest {

    private RecordValidator validatorOver(Path tempDir) {
        return new RecordValidator(new Store(tempDir.resolve("storage")));
    }

    @Test
    void requiredFieldIsRejectedWhenMissing(@TempDir Path tempDir) {
        RecordValidator v = validatorOver(tempDir);
        BusinessNeuron robert = Businesses.byUserId(Businesses.ROBERT);
        EntityDef clients = robert.entity("clients").orElseThrow();

        var ex = assertThrows(RecordValidator.ValidationException.class,
            () -> v.normalize(robert, clients, Map.of("email", "a@b.com"), null, null));
        assertTrue(ex.getMessage().contains("name"), ex.getMessage());
    }

    @Test
    void emailFieldRejectsMalformedAddressButAcceptsValidOne(@TempDir Path tempDir) {
        RecordValidator v = validatorOver(tempDir);
        BusinessNeuron robert = Businesses.byUserId(Businesses.ROBERT);
        EntityDef clients = robert.entity("clients").orElseThrow();

        assertThrows(RecordValidator.ValidationException.class,
            () -> v.normalize(robert, clients, Map.of("name", "Acme", "email", "not-an-email"), null, null));

        Map<String,Object> ok = v.normalize(robert, clients, Map.of("name", "Acme", "email", "ops@acme.test"), null, null);
        assertEquals("ops@acme.test", ok.get("email"));
    }

    @Test
    void isoDateFieldRejectsBadFormatButAcceptsValidDate(@TempDir Path tempDir) {
        RecordValidator v = validatorOver(tempDir);
        BusinessNeuron marie = Businesses.byUserId(Businesses.MARIE);
        EntityDef reservations = marie.entity("reservations").orElseThrow();

        assertThrows(RecordValidator.ValidationException.class,
            () -> v.normalize(marie, reservations, Map.of("guestName", "Dupont", "reservationDate", "09/07/2026", "partySize", "4"), null, null));

        Map<String,Object> ok = v.normalize(marie, reservations, Map.of("guestName", "Dupont", "reservationDate", "2026-09-07", "partySize", "4"), null, null);
        assertEquals("2026-09-07", ok.get("reservationDate"));
        assertEquals(4L, ok.get("partySize"));
    }

    @Test
    void integerFieldRejectsNonNumericText(@TempDir Path tempDir) {
        RecordValidator v = validatorOver(tempDir);
        BusinessNeuron marie = Businesses.byUserId(Businesses.MARIE);
        EntityDef reservations = marie.entity("reservations").orElseThrow();

        assertThrows(RecordValidator.ValidationException.class,
            () -> v.normalize(marie, reservations, Map.of("guestName", "Dupont", "reservationDate", "2026-09-07", "partySize", "four"), null, null));
    }

    @Test
    void decimalFieldIsScaledToTwoPlaces(@TempDir Path tempDir) {
        RecordValidator v = validatorOver(tempDir);
        BusinessNeuron marie = Businesses.byUserId(Businesses.MARIE);
        EntityDef menuItems = marie.entity("menu-items").orElseThrow();

        Map<String,Object> ok = v.normalize(marie, menuItems, Map.of("name", "Soupe", "category", "Entrées", "price", "9.5"), null, null);
        assertEquals("9.50", String.valueOf(ok.get("price")));
    }

    @Test
    void enumFieldRejectsValueOutsideDeclaredSet(@TempDir Path tempDir) {
        // Every other declared enum field in this spec is workflow-governed (forced to the
        // workflow's derived initial state on create - see below), so only Sofia's ungoverned
        // properties.status actually rejects an out-of-range value at create time.
        RecordValidator v = validatorOver(tempDir);
        BusinessNeuron sofia = Businesses.byUserId(Businesses.SOFIA);
        EntityDef properties = sofia.entity("properties").orElseThrow();

        var ex = assertThrows(RecordValidator.ValidationException.class,
            () -> v.normalize(sofia, properties, Map.of("name", "Casa Azul", "address", "Calle Mayor 1", "status", "BOGUS"), null, null));
        assertTrue(ex.getMessage().contains("status"), ex.getMessage());
    }

    @Test
    void enumFieldDisplaysLocalizedLabelButStoresCanonicalValue(@TempDir Path tempDir) {
        RecordValidator v = validatorOver(tempDir);
        BusinessNeuron sofia = Businesses.byUserId(Businesses.SOFIA);
        EntityDef properties = sofia.entity("properties").orElseThrow();
        FieldDef status = properties.field("status").orElseThrow();

        assertEquals("Ocupada", status.localizedEnumLabels().get("OCCUPIED"));
        Map<String,Object> record = v.normalize(sofia, properties, Map.of("name", "Casa Azul", "address", "Calle Mayor 1", "status", "occupied"), null, null);
        assertEquals("OCCUPIED", record.get("status"), "canonical uppercase value must be what's persisted");
    }

    @Test
    void referenceFieldResolvesOnlyWithinTheSameBusinessPartition(@TempDir Path tempDir) {
        Store store = new Store(tempDir.resolve("storage"));
        RecordValidator v = new RecordValidator(store);
        BusinessNeuron robert = Businesses.byUserId(Businesses.ROBERT);
        EntityDef clients = robert.entity("clients").orElseThrow();
        EntityDef engagements = robert.entity("engagements").orElseThrow();

        Map<String,Object> clientNormalized = v.normalize(robert, clients, Map.of("name", "Acme", "email", "a@acme.test"), null, null);
        Map<String,Object> clientRecord = store.create(robert.storageKind("clients"), clientNormalized);
        String clientId = String.valueOf(clientRecord.get("id"));

        assertThrows(RecordValidator.ValidationException.class,
            () -> v.normalize(robert, engagements, Map.of("name", "Phase 1", "clientId", "999999"), null, null));

        Map<String,Object> engagementNormalized = v.normalize(robert, engagements,
            Map.of("name", "Phase 1", "clientId", clientId), null, null);
        assertEquals(clientId, engagementNormalized.get("clientId"));
        assertEquals("PLANNED", engagementNormalized.get("status"));
    }

    @Test
    void workflowGovernedStateFieldIsForcedToInitialStateOnCreateAndCannotBeChangedDirectly(@TempDir Path tempDir) {
        Store store = new Store(tempDir.resolve("storage"));
        RecordValidator v = new RecordValidator(store);
        BusinessNeuron marie = Businesses.byUserId(Businesses.MARIE);
        EntityDef orders = marie.entity("orders").orElseThrow();

        Map<String,Object> created = v.normalize(marie, orders, Map.of("tableNumber", "12", "status", "SERVED", "total", "42.00"), null, null);
        assertEquals("NEW", created.get("status"));

        Map<String,Object> record = store.create(marie.storageKind("orders"), created);
        String id = String.valueOf(record.get("id"));

        var ex = assertThrows(RecordValidator.ValidationException.class,
            () -> v.normalize(marie, orders, Map.of("status", "SERVED"), record, id));
        assertTrue(ex.getMessage().contains("transition"), ex.getMessage());

        Map<String,Object> updated = v.normalize(marie, orders, Map.of("tableNumber", "14"), record, id);
        assertEquals("NEW", updated.get("status"));
        assertEquals("14", updated.get("tableNumber"));
    }

    @Test
    void sofiaPropertyStatusIsAPlainSettableEnumWithNoGoverningWorkflow(@TempDir Path tempDir) {
        RecordValidator v = validatorOver(tempDir);
        BusinessNeuron sofia = Businesses.byUserId(Businesses.SOFIA);
        EntityDef properties = sofia.entity("properties").orElseThrow();

        assertTrue(sofia.workflowFor("properties").isEmpty(), "properties must have no governing workflow");

        Map<String,Object> created = v.normalize(sofia, properties,
            Map.of("name", "Casa Azul", "address", "Calle Mayor 1", "status", "occupied"), null, null);
        assertEquals("OCCUPIED", created.get("status"));

        Store store = new Store(tempDir.resolve("storage2"));
        RecordValidator v2 = new RecordValidator(store);
        Map<String,Object> record = store.create(sofia.storageKind("properties"), created);
        String id = String.valueOf(record.get("id"));
        Map<String,Object> updated = v2.normalize(sofia, properties, Map.of("status", "MAINTENANCE"), record, id);
        assertEquals("MAINTENANCE", updated.get("status"));
    }

    @Test
    void deleteIsRestrictedWhileAReferencingRecordExists(@TempDir Path tempDir) {
        Store store = new Store(tempDir.resolve("storage"));
        RecordValidator v = new RecordValidator(store);
        BusinessNeuron hans = Businesses.byUserId(Businesses.HANS);
        EntityDef sellers = hans.entity("sellers").orElseThrow();
        EntityDef listings = hans.entity("listings").orElseThrow();

        Map<String,Object> sellerNormalized = v.normalize(hans, sellers, Map.of("name", "Seller One", "email", "s1@shop.test"), null, null);
        Map<String,Object> seller = store.create(hans.storageKind("sellers"), sellerNormalized);
        String sellerId = String.valueOf(seller.get("id"));

        Map<String,Object> listingNormalized = v.normalize(hans, listings, Map.of("title", "Widget", "sellerId", sellerId, "price", "19.99"), null, null);
        store.create(hans.storageKind("listings"), listingNormalized);

        var ex = assertThrows(RecordValidator.ValidationException.class,
            () -> v.checkDeleteAllowed(hans, "sellers", sellerId));
        assertTrue(ex.getMessage().contains("still references"), ex.getMessage());
    }

    @Test
    void everyFieldsDeclaredUiControlMatchesTheTypeDerivedControl() {
        for (BusinessNeuron business : Businesses.ALL) {
            for (EntityDef entity : business.entities()) {
                for (FieldDef f : entity.fields()) {
                    assertEquals(UiNeuron.CONTROL_FOR_TYPE.get(f.type()), f.uiControl(),
                        business.userId() + "." + entity.id() + "." + f.id());
                    String expectedScope = f.type() == BusinessModel.FieldType.REFERENCE ? "ACTIVE_BUSINESS_PARTITION_ONLY" : "NOT_APPLICABLE";
                    assertEquals(expectedScope, f.pickerScope(), business.userId() + "." + entity.id() + "." + f.id());
                }
            }
        }
    }

    @Test
    void deriveInitialStateHoldsForEveryDeclaredWorkflow() {
        for (BusinessNeuron business : Businesses.ALL) {
            for (WorkflowDef workflow : business.workflows()) {
                EntityDef entity = business.entity(workflow.subjectEntity()).orElseThrow();
                FieldDef stateField = entity.field(workflow.stateField()).orElseThrow();
                String initial = workflow.initialState(stateField.enumValues());
                assertNotNull(initial, workflow.id());
                assertTrue(stateField.enumValues().contains(initial), workflow.id());
            }
        }
    }
}

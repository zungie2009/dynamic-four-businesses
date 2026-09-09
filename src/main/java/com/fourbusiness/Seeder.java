package com.fourbusiness;

import com.fourbusiness.BusinessModel.BusinessNeuron;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Applies the deterministic demo dataset the spec's {@code seedDataContract} requires but never
 * itself supplies (see README - this is the one place in the build where content had to be
 * authored rather than transcribed from the decoded script, and it is called out there as such,
 * not hidden). One dataset per business partition, applied at most once per partition
 * ({@code applyWhen: SEED_VERSION_MARKER_ABSENT}): a durable marker record
 * ({@code businessAppId::_seed / MARKER}) is written after a successful seed and checked before
 * every attempt, so a restart never re-seeds or duplicates records
 * ({@code idempotence: DURABLE_MARKER_PREVENTS_RESEED_AFTER_USER_DELETION} - this application has
 * no user-deletion feature at all, so the marker can in practice never be cleared from underneath
 * itself, which trivially satisfies that rule). Every seeded reference (engagement -> client,
 * invoice -> engagement, etc.) resolves to a record created earlier in the same partition within
 * this same pass, per {@code seedDataContract.referenceRule: SEED_REFERENCES_RESOLVE_WITHIN_SAME_PARTITION}.
 *
 * <p>Seeding also initializes each business's Administration record
 * ({@code businessName} defaulted to the declared business name, {@code theme} to the declared
 * default theme, {@code notifications} defaulted to enabled, and {@code schemaVersion} - see
 * {@link #SCHEMA_VERSION} and the README section on why "1" was chosen) so that
 * {@code GET /administration} never has to invent a response before a user has saved anything.
 */
public final class Seeder {
    private Seeder() {}

    public static final String SEED_VERSION = "DEMO_SEED_V1";

    /**
     * The spec requires Administration to persist and display a {@code schemaVersion} per
     * business partition, and includes it in the canonical storage key tuple, but never assigns
     * an actual value anywhere in the script. "1" is used uniformly as the starting version for
     * every business's schema - a documented judgment call (see README), not a value taken from
     * the specification.
     */
    public static final String SCHEMA_VERSION = "1";

    public interface AuditSink {
        void audit(BusinessNeuron business, String entityId, String operation, String target);
    }

    public static void seedIfNeeded(Store store, AuditSink audit) {
        for (BusinessNeuron business : Businesses.ALL) {
            seedBusinessIfNeeded(store, business, audit);
        }
    }

    private static void seedBusinessIfNeeded(Store store, BusinessNeuron business, AuditSink audit) {
        if (store.get(business.seedMarkerKind(), "MARKER") != null) return;

        store.put(business.administrationKind(), "SETTINGS", Map.of(
            "businessName", business.businessName(),
            "theme", business.defaultTheme(),
            "notifications", true,
            "schemaVersion", SCHEMA_VERSION
        ));

        switch (business.userId()) {
            case Businesses.ROBERT -> seedRobert(store, business, audit);
            case Businesses.MARIE -> seedMarie(store, business, audit);
            case Businesses.HANS -> seedHans(store, business, audit);
            case Businesses.SOFIA -> seedSofia(store, business, audit);
            default -> throw new IllegalStateException("No seed dataset authored for user: " + business.userId());
        }

        store.put(business.seedMarkerKind(), "MARKER", Map.of("seedVersion", SEED_VERSION));
        audit.audit(business, "_seed", "DEMO_SEED_COMPLETED", SEED_VERSION);
    }

    private static String create(Store store, AuditSink audit, BusinessNeuron business, String entityId, Map<String,Object> fields) {
        Map<String,Object> record = store.create(business.storageKind(entityId), fields);
        String id = String.valueOf(record.get("id"));
        audit.audit(business, entityId, "SEED_RECORD_CREATED", id);
        return id;
    }

    private static void seedRobert(Store store, BusinessNeuron b, AuditSink audit) {
        String client1 = create(store, audit, b, "clients", Map.of(
            "name", "Priya Shah", "email", "priya.shah@acme.example", "company", "Acme Corporation"));
        String client2 = create(store, audit, b, "clients", Map.of(
            "name", "Marcus Webb", "email", "marcus.webb@novaretail.example", "company", "Nova Retail Group"));

        String engagement1 = create(store, audit, b, "engagements", Map.of(
            "name", "Website Redesign", "clientId", client1, "status", "ACTIVE"));
        String engagement2 = create(store, audit, b, "engagements", Map.of(
            "name", "Q3 Financial Audit", "clientId", client2, "status", "PLANNED"));

        create(store, audit, b, "invoices", Map.of(
            "number", "INV-1001", "engagementId", engagement1, "amount", new BigDecimal("4500.00"), "status", "ISSUED"));
        create(store, audit, b, "invoices", Map.of(
            "number", "INV-1002", "engagementId", engagement2, "amount", new BigDecimal("1200.00"), "status", "DRAFT"));
    }

    private static void seedMarie(Store store, BusinessNeuron b, AuditSink audit) {
        create(store, audit, b, "menu-items", Map.of("name", "Soupe à l'oignon", "category", "Entrées", "price", new BigDecimal("8.50")));
        create(store, audit, b, "menu-items", Map.of("name", "Coq au vin", "category", "Plats", "price", new BigDecimal("18.00")));
        create(store, audit, b, "menu-items", Map.of("name", "Tarte Tatin", "category", "Desserts", "price", new BigDecimal("7.00")));

        create(store, audit, b, "reservations", Map.of("guestName", "Dupont", "reservationDate", "2026-09-15", "partySize", 4L));
        create(store, audit, b, "reservations", Map.of("guestName", "Moreau", "reservationDate", "2026-09-16", "partySize", 2L));

        create(store, audit, b, "orders", Map.of("tableNumber", "5", "status", "PREPARING", "total", new BigDecimal("42.50")));
        create(store, audit, b, "orders", Map.of("tableNumber", "12", "status", "NEW", "total", new BigDecimal("18.00")));
    }

    private static void seedHans(Store store, BusinessNeuron b, AuditSink audit) {
        String seller1 = create(store, audit, b, "sellers", Map.of("name", "Klaus Bergmann", "email", "klaus.bergmann@markt.example", "status", "ACTIVE"));
        create(store, audit, b, "sellers", Map.of("name", "Greta Voss", "email", "greta.voss@markt.example", "status", "PENDING"));

        String listing1 = create(store, audit, b, "listings", Map.of("title", "Handgefertigte Vase", "sellerId", seller1, "price", new BigDecimal("34.90")));
        String listing2 = create(store, audit, b, "listings", Map.of("title", "Vintage Kamera", "sellerId", seller1, "price", new BigDecimal("120.00")));

        create(store, audit, b, "orders", Map.of("number", "BES-2001", "listingId", listing1, "status", "PLACED"));
        create(store, audit, b, "orders", Map.of("number", "BES-2002", "listingId", listing2, "status", "PAID"));
    }

    private static void seedSofia(Store store, BusinessNeuron b, AuditSink audit) {
        String property1 = create(store, audit, b, "properties", Map.of("name", "Casa Jazmín", "address", "Calle Mayor 12, Madrid", "status", "OCCUPIED"));
        String property2 = create(store, audit, b, "properties", Map.of("name", "Apartamento del Sol", "address", "Av. Libertad 45, Madrid", "status", "AVAILABLE"));

        String tenant1 = create(store, audit, b, "tenants", Map.of("name", "Elena Ruiz", "email", "elena.ruiz@correo.example", "phone", "+34 600 123 456"));

        create(store, audit, b, "leases", Map.of("propertyId", property1, "tenantId", tenant1, "status", "ACTIVE"));
        create(store, audit, b, "maintenance", Map.of("propertyId", property2, "description", "Revisión de la caldera", "status", "OPEN"));
    }
}

package com.fourbusiness;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starts the real HttpServer via {@link FourBusinessApplication#start} and drives it with a
 * plain {@link HttpClient} (no login - see {@link FourBusinessApplication}'s class javadoc on
 * the demo user switcher not being authentication). Covers unknown user/entity/workflow
 * rejection, the new {@code /records/{entityId}/{recordId}/transition} route, a full
 * Administration read/save/immutable-field round trip, seeded data being present on first
 * boot, delete-restrict over HTTP, and - most importantly - the cross-business storage
 * isolation guarantee between Marie's and Hans's identically-named {@code orders} entities.
 */
class WebIntegrationTest {
    private static HttpServer server;
    private static String base;
    private static HttpClient client;

    @BeforeAll
    static void startServer(@TempDir Path tempDir) throws Exception {
        System.setProperty("fourbusiness.storage.dir", tempDir.resolve("storage").toString());
        System.setProperty("fourbusiness.http.port", "0");
        Config config = Config.load();
        server = FourBusinessApplication.start(config);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
        System.clearProperty("fourbusiness.storage.dir");
        System.clearProperty("fourbusiness.http.port");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> putJson(String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> delete(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String idOf(String json) {
        int idx = json.indexOf("\"id\":\"");
        assertTrue(idx >= 0, "expected an id in " + json);
        int start = idx + "\"id\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }

    @Test
    void rootShellServesForAnyPath() throws Exception {
        HttpResponse<String> r = get("/");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().toLowerCase().contains("html"), r.body());
    }

    @Test
    void containerListsAllFourDemoUsersAndDeclaresItselfNonProduction() throws Exception {
        HttpResponse<String> r = get("/api/container");
        assertEquals(200, r.statusCode());
        for (String userId : new String[] {"robert", "marie", "hans", "sofia"}) {
            assertTrue(r.body().contains("\"" + userId + "\""), r.body());
        }
        assertTrue(r.body().contains("\"productionAuthentication\":false"), r.body());
    }

    @Test
    void seededDataIsPresentOnFirstBoot() throws Exception {
        HttpResponse<String> clients = get("/api/robert/records/clients");
        assertEquals(200, clients.statusCode());
        assertTrue(clients.body().contains("Priya Shah") || clients.body().contains("Marcus Webb"), clients.body());

        HttpResponse<String> properties = get("/api/sofia/records/properties");
        assertTrue(properties.body().contains("Casa Jazmín") || properties.body().contains("Apartamento del Sol"), properties.body());
    }

    @Test
    void unknownUserIsRejected() throws Exception {
        assertEquals(404, get("/api/nobody/schema").statusCode());
        assertEquals(404, get("/api/nobody/records/clients").statusCode());
    }

    @Test
    void unknownEntityForAKnownBusinessIsRejected() throws Exception {
        HttpResponse<String> r = get("/api/robert/records/not-a-real-entity");
        assertEquals(404, r.statusCode());
    }

    @Test
    void transitionOnAnEntityWithNoGoverningWorkflowIsRejected() throws Exception {
        HttpResponse<String> listResp = get("/api/sofia/records/properties");
        String propertyId = idOf(listResp.body());
        HttpResponse<String> r = postJson("/api/sofia/records/properties/" + propertyId + "/transition", "{\"to\":\"OCCUPIED\"}");
        assertEquals(404, r.statusCode());
    }

    @Test
    void activeEntityMismatchIsRejected() throws Exception {
        HttpResponse<String> r = postJson("/api/robert/records/clients",
            "{\"name\":\"Acme\",\"email\":\"a@acme.test\",\"_activeEntity\":\"engagements\"}");
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("_activeEntity"), r.body());
    }

    @Test
    void robertClientEngagementInvoiceCrudAndTransitionRouteRoundTrip() throws Exception {
        HttpResponse<String> createClient = postJson("/api/robert/records/clients", "{\"name\":\"Acme Corp\",\"email\":\"ops@acme.test\"}");
        assertEquals(200, createClient.statusCode(), createClient.body());
        String clientId = idOf(createClient.body());

        HttpResponse<String> createEngagement = postJson("/api/robert/records/engagements",
            "{\"name\":\"Phase 1\",\"clientId\":\"" + clientId + "\"}");
        assertEquals(200, createEngagement.statusCode(), createEngagement.body());
        String engagementId = idOf(createEngagement.body());
        assertTrue(createEngagement.body().contains("\"status\":\"PLANNED\""), createEngagement.body());

        HttpResponse<String> badTransition = postJson("/api/robert/records/engagements/" + engagementId + "/transition", "{\"to\":\"COMPLETED\"}");
        assertEquals(400, badTransition.statusCode(), "PLANNED -> COMPLETED is not a declared transition");

        HttpResponse<String> toActive = postJson("/api/robert/records/engagements/" + engagementId + "/transition", "{\"to\":\"ACTIVE\"}");
        assertEquals(200, toActive.statusCode(), toActive.body());
        assertTrue(toActive.body().contains("\"status\":\"ACTIVE\""), toActive.body());

        HttpResponse<String> directEdit = putJson("/api/robert/records/engagements/" + engagementId, "{\"status\":\"COMPLETED\"}");
        assertEquals(400, directEdit.statusCode(), "status must not be editable via the generic update route");

        HttpResponse<String> createInvoice = postJson("/api/robert/records/invoices",
            "{\"number\":\"INV-999\",\"engagementId\":\"" + engagementId + "\",\"amount\":\"1500\"}");
        assertEquals(200, createInvoice.statusCode(), createInvoice.body());
        assertTrue(createInvoice.body().contains("\"status\":\"DRAFT\""), createInvoice.body());

        HttpResponse<String> deleteRestricted = delete("/api/robert/records/engagements/" + engagementId);
        assertEquals(400, deleteRestricted.statusCode(), "an invoice still references this engagement");

        HttpResponse<String> deleteClientRestricted = delete("/api/robert/records/clients/" + clientId);
        assertEquals(400, deleteClientRestricted.statusCode(), "the engagement still references this client");
    }

    @Test
    void administrationGetReflectsSeededDefaultsAndPutValidatesRequiredAndAllowedValues() throws Exception {
        HttpResponse<String> get1 = get("/api/hans/administration");
        assertEquals(200, get1.statusCode());
        assertTrue(get1.body().contains("\"schemaVersion\":\"1\""), get1.body());
        assertTrue(get1.body().contains("\"notifications\":true"), get1.body());

        HttpResponse<String> badTheme = putJson("/api/hans/administration",
            "{\"businessName\":\"Hans Marketplace\",\"theme\":\"not-a-real-theme\",\"notifications\":true}");
        assertEquals(400, badTheme.statusCode());

        HttpResponse<String> missingBusinessName = putJson("/api/hans/administration",
            "{\"businessName\":\"\",\"theme\":\"midnight-market\",\"notifications\":true}");
        assertEquals(400, missingBusinessName.statusCode());

        HttpResponse<String> ok = putJson("/api/hans/administration",
            "{\"businessName\":\"Hans' Marketplace\",\"theme\":\"midnight-market\",\"notifications\":false}");
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("Hans' Marketplace"), ok.body());

        // The saved business name must propagate to the schema endpoint and the container listing.
        HttpResponse<String> schema = get("/api/hans/schema");
        assertTrue(schema.body().contains("Hans' Marketplace"), schema.body());
        HttpResponse<String> container = get("/api/container");
        assertTrue(container.body().contains("Hans' Marketplace"), container.body());
    }

    @Test
    void administrationRejectsAnAttemptToEditAReadOnlyField() throws Exception {
        HttpResponse<String> r = putJson("/api/marie/administration",
            "{\"businessName\":\"Restaurant\",\"theme\":\"bistro-dark\",\"notifications\":true,\"locale\":\"de-DE\"}");
        assertEquals(400, r.statusCode(), "locale is visible but must not be editable");
    }

    /**
     * The single most safety-critical property in this application: Marie's business and Hans's
     * business both declare an entity literally named {@code orders}, and
     * {@code persistenceNeuron.keyRule} requires that "No storage index or lookup may use
     * entityId alone." This creates a record in each business's {@code orders} (on top of the
     * two each business is already seeded with) and confirms neither business's record list, nor
     * a cross-business direct fetch by id, ever surfaces the other's data.
     */
    @Test
    void marieAndHansOrdersNeverContaminateEachOther() throws Exception {
        HttpResponse<String> marieOrder = postJson("/api/marie/records/orders", "{\"tableNumber\":\"7\",\"total\":\"23.50\"}");
        assertEquals(200, marieOrder.statusCode(), marieOrder.body());
        String marieOrderId = idOf(marieOrder.body());

        HttpResponse<String> hansSeller = postJson("/api/hans/records/sellers", "{\"name\":\"Cross Seller\",\"email\":\"x@shop.test\"}");
        String hansSellerId = idOf(hansSeller.body());
        HttpResponse<String> hansListing = postJson("/api/hans/records/listings",
            "{\"title\":\"Cross Listing\",\"sellerId\":\"" + hansSellerId + "\",\"price\":\"5.00\"}");
        String hansListingId = idOf(hansListing.body());
        HttpResponse<String> hansOrder = postJson("/api/hans/records/orders",
            "{\"number\":\"HZ-1\",\"listingId\":\"" + hansListingId + "\"}");
        assertEquals(200, hansOrder.statusCode(), hansOrder.body());
        String hansOrderId = idOf(hansOrder.body());

        assertNotEquals(marieOrderId, hansOrderId, "test setup should produce distinct ids, or this test proves nothing");

        HttpResponse<String> marieList = get("/api/marie/records/orders");
        assertTrue(marieList.body().contains(marieOrderId), marieList.body());
        assertFalse(marieList.body().contains(hansOrderId), "Marie's orders list must never contain Hans's order id: " + marieList.body());
        assertFalse(marieList.body().contains("HZ-1"), "Marie's orders list must never contain Hans's field data: " + marieList.body());

        HttpResponse<String> hansList = get("/api/hans/records/orders");
        assertTrue(hansList.body().contains(hansOrderId), hansList.body());
        assertFalse(hansList.body().contains(marieOrderId), "Hans's orders list must never contain Marie's order id: " + hansList.body());

        HttpResponse<String> crossFetch = get("/api/hans/records/orders/" + marieOrderId);
        assertEquals(404, crossFetch.statusCode(), "Marie's order id must not resolve inside Hans's orders partition");
    }
}

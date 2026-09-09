package com.fourbusiness;

import com.fourbusiness.BusinessModel.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Entry point and HTTP boundary for the Four Business Demo, built from
 * {@code fourbusinesscompressedscript4.jsonc} (format {@code LLM_AST_TOKEN_MANIFEST_V4}, source
 * {@code RECURSIVE_FOUR_BUSINESS_HIERARCHY_V4} - the revision of the script that added the full
 * Administration surface, UI control mapping, localized enum/transition labels, an audit
 * vocabulary, and a seed-data contract; see README for the decode and independent verification
 * this build is based on).
 *
 * <p>One shared UI capability projects exactly one of four independent business applications at
 * a time, selected by a demo user switcher ({@code containerSelf.accessMode} is explicitly
 * {@code DEMONSTRATION_USER_SWITCHER}, not authentication - no login screen is implemented here).
 * Every request is scoped by URL to exactly one business
 * ({@code requestContextContract.recordRoute}/{@code transitionRoute}, matched literally below)
 * and every persisted record lives under a storage key compounded from (businessAppId, entityId)
 * - see {@link BusinessModel.BusinessNeuron#storageKind} - never entityId alone, which is what
 * keeps Marie's and Hans's both-named {@code orders} apart (see
 * {@link com.fourbusiness.WebIntegrationTest#marieAndHansOrdersNeverContaminateEachOther}).
 */
public final class FourBusinessApplication {
    private final Store store;
    private final RecordValidator validator;

    private FourBusinessApplication(Config config) {
        this.store = new Store(config.storageDir());
        this.validator = new RecordValidator(store);
        Seeder.seedIfNeeded(store, this::audit);
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        HttpServer server = start(config);
        System.out.println("Four Business Demo listening on http://" + config.host() + ":" + server.getAddress().getPort() + "/");
        System.out.println("Storage: " + config.storageDir().toAbsolutePath());
        System.out.println("Businesses: " + Businesses.ALL.stream().map(BusinessNeuron::userId).toList());
    }

    public static synchronized HttpServer start(Config config) throws IOException {
        FourBusinessApplication app = new FourBusinessApplication(config);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        } catch (java.net.BindException e) {
            throw new java.net.BindException("Address " + config.host() + ":" + config.port()
                + " is already in use. Set -Dfourbusiness.http.port=0 (OS-assigned) or another free port and restart.");
        }
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String query = exchange.getRequestURI().getRawQuery();

            if (path.startsWith("/api/")) {
                try {
                    routeApi(exchange, path.substring("/api/".length()), method, query);
                } catch (RecordValidator.ValidationException e) {
                    respondJson(exchange, 400, Json.write(Map.of("success", false, "message", e.getMessage())));
                }
                return;
            }

            try (InputStream in = FourBusinessApplication.class.getResourceAsStream("/static/index.html")) {
                byte[] bytes = in.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private void routeApi(HttpExchange exchange, String rest, String method, String query) throws IOException {
        if (rest.equals("container") && method.equals("GET")) {
            respondJson(exchange, 200, Json.write(containerInfo()));
            return;
        }

        String[] parts = rest.split("/", 2);
        String userId = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
        String remainder = parts.length > 1 ? parts[1] : "";

        BusinessNeuron business = Businesses.byUserId(userId);
        if (business == null) {
            respondJson(exchange, 404, Json.write(Map.of("success", false, "message", "Unknown user: " + userId)));
            return;
        }

        if (remainder.equals("schema") && method.equals("GET")) {
            respondJson(exchange, 200, Json.write(schemaOf(business)));
            return;
        }
        if (remainder.equals("administration")) {
            handleAdministration(exchange, business, method);
            return;
        }
        if (remainder.startsWith("records/")) {
            handleRecords(exchange, business, remainder.substring("records/".length()), method, query);
            return;
        }
        respondJson(exchange, 404, Json.write(Map.of("success", false, "message", "Unknown route")));
    }

    private void handleRecords(HttpExchange exchange, BusinessNeuron business, String rest, String method, String query) throws IOException {
        int firstSlash = rest.indexOf('/');
        String entityId = URLDecoder.decode(firstSlash < 0 ? rest : rest.substring(0, firstSlash), StandardCharsets.UTF_8);
        String remainder2 = firstSlash < 0 ? "" : rest.substring(firstSlash + 1);
        int secondSlash = remainder2.indexOf('/');
        String recordId = remainder2.isEmpty() ? null : URLDecoder.decode(secondSlash < 0 ? remainder2 : remainder2.substring(0, secondSlash), StandardCharsets.UTF_8);
        String trailing = secondSlash < 0 ? null : remainder2.substring(secondSlash + 1);

        Optional<EntityDef> entityOpt = business.entity(entityId);
        if (entityOpt.isEmpty()) {
            respondJson(exchange, 404, Json.write(Map.of("success", false, "message",
                "'" + entityId + "' is not declared for " + business.businessName())));
            return;
        }
        EntityDef entity = entityOpt.get();
        String kind = business.storageKind(entityId);

        if (trailing != null) {
            if (!trailing.equals("transition") || !method.equals("POST")) {
                respondJson(exchange, 404, Json.write(Map.of("success", false, "message", "Unknown route")));
                return;
            }
            handleTransition(exchange, business, entity, recordId);
            return;
        }

        if (recordId == null && method.equals("GET")) { respondJson(exchange, 200, Json.write(listRecords(business, entity, query))); return; }
        if (recordId == null && method.equals("POST")) {
            Map<String,Object> body = readJsonBody(exchange);
            requireActiveEntityConsistency(body, entityId, business);
            Map<String,Object> normalized = validator.normalize(business, entity, body, null, null);
            Map<String,Object> created = store.create(kind, normalized);
            audit(business, entityId, "RECORD_CREATED", String.valueOf(created.get("id")));
            respondJson(exchange, 200, Json.write(Map.of("success", true, "record", created)));
            return;
        }
        if (recordId != null && method.equals("GET")) {
            Map<String,Object> record = store.get(kind, recordId);
            if (record == null) { respondJson(exchange, 404, Json.write(Map.of("success", false, "message", "Not found: " + recordId))); return; }
            respondJson(exchange, 200, Json.write(record));
            return;
        }
        if (recordId != null && (method.equals("PUT") || method.equals("PATCH"))) {
            Map<String,Object> existing = store.get(kind, recordId);
            if (existing == null) { respondJson(exchange, 404, Json.write(Map.of("success", false, "message", "Not found: " + recordId))); return; }
            Map<String,Object> body = readJsonBody(exchange);
            requireActiveEntityConsistency(body, entityId, business);
            Map<String,Object> normalized = validator.normalize(business, entity, body, existing, recordId);
            Map<String,Object> updated = store.update(kind, recordId, normalized);
            respondJson(exchange, 200, Json.write(Map.of("success", true, "record", updated)));
            return;
        }
        if (recordId != null && method.equals("DELETE")) {
            if (store.get(kind, recordId) == null) { respondJson(exchange, 404, Json.write(Map.of("success", false, "message", "Not found: " + recordId))); return; }
            validator.checkDeleteAllowed(business, entityId, recordId);
            store.remove(kind, recordId);
            audit(business, entityId, "RECORD_DELETED", recordId);
            respondJson(exchange, 200, Json.write(Map.of("success", true)));
            return;
        }
        respondJson(exchange, 405, Json.write(Map.of("success", false, "message", "Method not allowed")));
    }

    /**
     * {@code requestContextContract.transitionRoute}:
     * {@code /api/{selectedUserId}/records/{entityId}/{recordId}/transition}. The workflow that
     * governs {@code entityId} (if any) is resolved server-side - the client only names the
     * target state ({@code "to"}) - and {@code uiNeuron.unrestrictedStatusEditing: false} means
     * the current-to-target pair must appear together in that workflow's declared transitions or
     * the request is rejected outright.
     */
    private void handleTransition(HttpExchange exchange, BusinessNeuron business, EntityDef entity, String recordId) throws IOException {
        if (recordId == null) { respondJson(exchange, 404, Json.write(Map.of("success", false, "message", "Unknown route"))); return; }
        Optional<WorkflowDef> workflowOpt = business.workflowFor(entity.id());
        if (workflowOpt.isEmpty()) {
            respondJson(exchange, 404, Json.write(Map.of("success", false, "message",
                "No workflow governs '" + entity.id() + "' for " + business.businessName())));
            return;
        }
        WorkflowDef workflow = workflowOpt.get();
        String kind = business.storageKind(entity.id());
        Map<String,Object> record = store.get(kind, recordId);
        if (record == null) { respondJson(exchange, 404, Json.write(Map.of("success", false, "message", "Not found: " + recordId))); return; }

        Map<String,Object> body = readJsonBody(exchange);
        requireActiveEntityConsistency(body, entity.id(), business);

        String to = Json.text(body.get("to"));
        String current = String.valueOf(record.get(workflow.stateField()));
        if (to == null || !workflow.allows(current, to)) {
            respondJson(exchange, 400, Json.write(Map.of("success", false,
                "message", "Invalid transition from " + current + " to " + to + " for " + workflow.id())));
            return;
        }
        Map<String,Object> updated = store.update(kind, recordId, Map.of(workflow.stateField(), to));
        audit(business, entity.id(), "STATE_TRANSITION", recordId);
        respondJson(exchange, 200, Json.write(Map.of("success", true, "record", updated)));
    }

    /**
     * {@code requestContextContract.submissionConsistency}: "_activeEntity MUST equal route
     * entityId", {@code crossBusinessOverride: PROHIBITED} - a client payload naming a different
     * entity or business than the URL it was submitted to is rejected outright, guarding against
     * e.g. a stale form left open across a user switch submitting into the wrong business.
     */
    private void requireActiveEntityConsistency(Map<String,Object> body, String entityId, BusinessNeuron business) {
        Object activeEntity = body.get("_activeEntity");
        if (activeEntity != null && !entityId.equals(String.valueOf(activeEntity))) {
            throw new RecordValidator.ValidationException("_activeEntity ('" + activeEntity + "') does not match the route entity ('" + entityId + "')");
        }
        Object activeBusiness = body.get("_business");
        if (activeBusiness != null && !business.applicationId().equals(String.valueOf(activeBusiness))
                && !business.userId().equals(String.valueOf(activeBusiness))) {
            throw new RecordValidator.ValidationException("_business ('" + activeBusiness + "') does not match the resolved business ('" + business.applicationId() + "')");
        }
    }

    /**
     * {@code administrationNeuron}: a standalone, per-business surface with three editable,
     * persisted fields ({@code businessName}, {@code theme}, {@code notifications}) and three
     * visible-but-immutable ones ({@code locale}, {@code schemaVersion}, {@code persistencePartition}).
     * {@code auditResponsibility: NONE_USE_AUDIT_NEURON} - this handler emits audit events through
     * the same shared {@link #audit} helper every other mutation uses, rather than any
     * Administration-specific auditing logic.
     */
    private void handleAdministration(HttpExchange exchange, BusinessNeuron business, String method) throws IOException {
        if (method.equals("GET")) {
            respondJson(exchange, 200, Json.write(administrationView(business)));
            return;
        }
        if (method.equals("PUT")) {
            Map<String,Object> body = readJsonBody(exchange);
            Map<String,Object> current = currentAdministration(business);

            String businessName = Json.text(body.get("businessName"));
            if (businessName == null || businessName.isBlank()) {
                throw new RecordValidator.ValidationException("'businessName' is required");
            }
            String theme = Json.text(body.get("theme"));
            if (theme == null || !business.allowedThemes().contains(theme)) {
                throw new RecordValidator.ValidationException("'theme' must be one of " + business.allowedThemes());
            }
            Boolean notifications = asBoolean(body.get("notifications"));
            if (notifications == null) {
                throw new RecordValidator.ValidationException("'notifications' is required and must be true or false");
            }
            rejectIfDifferentFromReadOnly(body, "locale", business.locale());
            rejectIfDifferentFromReadOnly(body, "schemaVersion", String.valueOf(current.get("schemaVersion")));
            rejectIfDifferentFromReadOnly(body, "persistencePartition", business.persistencePartition());

            String previousTheme = String.valueOf(current.get("theme"));
            Map<String,Object> merged = new LinkedHashMap<>(current);
            merged.put("businessName", businessName);
            merged.put("theme", theme);
            merged.put("notifications", notifications);
            store.put(business.administrationKind(), "SETTINGS", merged);

            audit(business, "_administration", "ADMINISTRATION_CHANGED", "SETTINGS");
            if (!theme.equals(previousTheme)) audit(business, "_administration", "THEME_CHANGED", theme);

            respondJson(exchange, 200, Json.write(Map.of("success", true, "administration", administrationView(business))));
            return;
        }
        respondJson(exchange, 405, Json.write(Map.of("success", false, "message", "Method not allowed")));
    }

    private void rejectIfDifferentFromReadOnly(Map<String,Object> body, String field, String currentValue) {
        if (body.containsKey(field)) {
            Object submitted = body.get(field);
            if (submitted != null && !currentValue.equals(String.valueOf(submitted))) {
                throw new RecordValidator.ValidationException("'" + field + "' is visible but cannot be edited");
            }
        }
    }

    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if (s.equalsIgnoreCase("true")) return Boolean.TRUE;
            if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        }
        return null;
    }

    private Map<String,Object> currentAdministration(BusinessNeuron business) {
        Map<String,Object> settings = store.get(business.administrationKind(), "SETTINGS");
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("businessName", settings != null && settings.get("businessName") != null ? settings.get("businessName") : business.businessName());
        out.put("theme", settings != null && settings.get("theme") != null ? settings.get("theme") : business.defaultTheme());
        out.put("notifications", settings != null && settings.get("notifications") != null ? settings.get("notifications") : Boolean.TRUE);
        out.put("schemaVersion", settings != null && settings.get("schemaVersion") != null ? settings.get("schemaVersion") : Seeder.SCHEMA_VERSION);
        return out;
    }

    private Map<String,Object> administrationView(BusinessNeuron business) {
        Map<String,Object> current = currentAdministration(business);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("businessName", current.get("businessName"));
        out.put("theme", current.get("theme"));
        out.put("allowedThemes", business.allowedThemes());
        out.put("notifications", current.get("notifications"));
        out.put("locale", business.locale());
        out.put("schemaVersion", current.get("schemaVersion"));
        out.put("persistencePartition", business.persistencePartition());
        out.put("accessWarning", Businesses.CONTAINER.accessWarning());
        out.put("fields", Businesses.ADMINISTRATION_FIELDS.stream().map(f -> {
            Map<String,Object> fm = new LinkedHashMap<>();
            fm.put("id", f.id());
            fm.put("labelKey", f.labelKey());
            fm.put("control", f.control());
            fm.put("persisted", f.persisted());
            fm.put("editable", f.editable());
            return fm;
        }).collect(Collectors.toList()));
        return out;
    }

    private void audit(BusinessNeuron business, String entityId, String operation, String target) {
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("userId", business.userId());
        event.put("businessAppId", business.applicationId());
        event.put("entityId", entityId);
        event.put("operation", operation);
        event.put("target", target);
        store.create(business.auditKind(), event);
    }

    private Map<String,Object> listRecords(BusinessNeuron business, EntityDef entity, String rawQuery) {
        Map<String,String> params = parseQuery(rawQuery);
        String q = params.remove("q");
        int page = Math.max(1, parseIntOr(params.remove("page"), 1));
        int pageSize = Math.max(1, parseIntOr(params.remove("pageSize"), 50));

        List<Map<String,Object>> all = store.all(business.storageKind(entity.id()));
        List<Map<String,Object>> filtered = all.stream()
            .filter(r -> q == null || q.isBlank() || r.values().stream().anyMatch(v -> v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))))
            .sorted(Comparator.comparing(r -> String.valueOf(r.getOrDefault("id", ""))))
            .collect(Collectors.toList());

        int from = Math.min((page - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        return Map.of("entityId", entity.id(), "count", filtered.size(), "page", page, "pageSize", pageSize, "records", filtered.subList(from, to));
    }

    private Map<String,Object> containerInfo() {
        List<Map<String,Object>> users = Businesses.ALL.stream().map(b -> {
            Map<String,Object> um = new LinkedHashMap<>();
            um.put("userId", b.userId());
            um.put("person", b.person());
            um.put("business", currentAdministration(b).get("businessName"));
            um.put("language", b.language());
            um.put("locale", b.locale());
            return um;
        }).collect(Collectors.toList());
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("defaultUser", Businesses.CONTAINER.defaultUser());
        out.put("users", users);
        out.put("accessMode", Businesses.CONTAINER.accessMode());
        out.put("accessWarning", Businesses.CONTAINER.accessWarning());
        out.put("productionAuthentication", Businesses.CONTAINER.productionAuthentication());
        return out;
    }

    private Map<String,Object> schemaOf(BusinessNeuron business) {
        String lang = business.locale().split("-")[0];
        List<Map<String,Object>> entities = business.entities().stream().map(e -> {
            Map<String,Object> em = new LinkedHashMap<>();
            em.put("id", e.id());
            em.put("name", e.name());
            em.put("displayField", e.displayField());
            em.put("secondaryDisplayField", e.secondaryDisplayField());
            em.put("fields", e.fields().stream().map(f -> {
                Map<String,Object> fm = new LinkedHashMap<>();
                fm.put("id", f.id());
                fm.put("label", f.label());
                fm.put("type", f.type().name());
                fm.put("required", f.required());
                fm.put("editable", f.editable());
                fm.put("generated", f.generated());
                fm.put("enumValues", f.enumValues());
                fm.put("localizedEnumLabels", f.localizedEnumLabels());
                fm.put("referenceEntity", f.referenceEntity());
                fm.put("uiControl", f.uiControl());
                fm.put("pickerScope", f.pickerScope());
                fm.put("precision", f.type() == BusinessModel.FieldType.DECIMAL_19_2 ? Map.of("fractionDigits", 2, "totalDigits", 19) : Map.of());
                return fm;
            }).collect(Collectors.toList()));
            em.put("workflowId", business.workflowFor(e.id()).map(WorkflowDef::id).orElse(null));
            return em;
        }).collect(Collectors.toList());

        List<Map<String,Object>> workflows = business.workflows().stream().map(w -> {
            Map<String,Object> wm = new LinkedHashMap<>();
            wm.put("id", w.id());
            wm.put("subjectEntity", w.subjectEntity());
            wm.put("stateField", w.stateField());
            wm.put("authority", w.authority());
            wm.put("uiTrigger", w.uiTrigger());
            wm.put("transitions", w.transitions().stream().map(t -> {
                Map<String,Object> tm = new LinkedHashMap<>();
                tm.put("from", t.from());
                tm.put("to", t.to());
                tm.put("actionLabel", t.localizedActionLabel().getOrDefault(lang, t.to()));
                return tm;
            }).collect(Collectors.toList()));
            return wm;
        }).collect(Collectors.toList());

        Map<String,Object> current = currentAdministration(business);

        Map<String,Object> out = new LinkedHashMap<>();
        out.put("userId", business.userId());
        out.put("person", business.person());
        out.put("business", current.get("businessName"));
        out.put("language", business.language());
        out.put("locale", business.locale());
        out.put("theme", current.get("theme"));
        out.put("allowedThemes", business.allowedThemes());
        out.put("navigationPosition", "BOTTOM_LEFT");
        out.put("entities", entities);
        out.put("workflows", workflows);
        return out;
    }

    private static int parseIntOr(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static Map<String,String> parseQuery(String raw) {
        Map<String,String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, value);
        }
        return out;
    }

    private static Map<String,Object> readJsonBody(HttpExchange exchange) {
        try {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            return Json.object(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

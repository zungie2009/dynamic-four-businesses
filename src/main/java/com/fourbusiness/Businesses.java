package com.fourbusiness;

import com.fourbusiness.BusinessModel.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the four declared {@code businessNeurons}, the container self-description, and the
 * administration surface definition from {@code /schema/businesses.json} - a script-generated,
 * lossless extract of the decoded {@code fourbusinesscompressedscript4.jsonc} AST (see README
 * for the decode/verification process). Loading a generated resource rather than hand-
 * transcribing every field into Java source, as the previous (V2 script) version of this app
 * did, removes the main risk at this size: 40 fields' worth of localized labels and 7
 * workflows' worth of 4-language transition labels is a lot of text to retype by hand without
 * introducing a typo that independent verification then has to catch.
 *
 * <p>The loaded counts are checked against the spec's own {@code semanticValidation} counts
 * (13 entities, 40 fields, 7 relationships, 7 workflows across 4 businesses) at class-init
 * time as a fail-fast guard against a corrupted or truncated resource file.
 */
public final class Businesses {
    private Businesses() {}

    public static final String ROBERT = "robert";
    public static final String MARIE = "marie";
    public static final String HANS = "hans";
    public static final String SOFIA = "sofia";

    public record AdminFieldDef(String id, String labelKey, String control, boolean persisted,
                                 boolean editable, boolean required, String source) {}

    public record ContainerInfo(String defaultUser, String accessMode, String accessWarning, boolean productionAuthentication) {}

    public static final ContainerInfo CONTAINER;
    public static final List<AdminFieldDef> ADMINISTRATION_FIELDS;
    private static final Map<String, BusinessNeuron> BY_USER_ID = new LinkedHashMap<>();
    public static final List<BusinessNeuron> ALL;

    static {
        Map<String,Object> root = loadResource();

        Map<String,Object> container = Json.asObject(root.get("container"));
        CONTAINER = new ContainerInfo(
            Json.text(container.get("defaultUser")),
            Json.text(container.get("accessMode")),
            Json.text(container.get("accessWarning")),
            Boolean.TRUE.equals(container.get("productionAuthentication"))
        );

        Map<String,Object> administration = Json.asObject(root.get("administration"));
        List<AdminFieldDef> adminFields = new ArrayList<>();
        for (Object raw : Json.asArray(administration.get("fields"))) {
            Map<String,Object> f = Json.asObject(raw);
            adminFields.add(new AdminFieldDef(
                Json.text(f.get("id")), Json.text(f.get("labelKey")), Json.text(f.get("control")),
                Boolean.TRUE.equals(f.get("persisted")), Boolean.TRUE.equals(f.get("editable")),
                Boolean.TRUE.equals(f.get("required")), Json.text(f.get("source"))
            ));
        }
        ADMINISTRATION_FIELDS = List.copyOf(adminFields);

        for (Object rawBusiness : Json.asArray(root.get("businesses"))) {
            BusinessNeuron b = parseBusiness(Json.asObject(rawBusiness));
            BY_USER_ID.put(b.userId(), b);
        }
        ALL = List.copyOf(BY_USER_ID.values());

        verifyCountsMatchSpec();
    }

    public static BusinessNeuron byUserId(String userId) { return BY_USER_ID.get(userId); }
    public static boolean isKnownUser(String userId) { return userId != null && BY_USER_ID.containsKey(userId); }

    private static BusinessNeuron parseBusiness(Map<String,Object> b) {
        List<EntityDef> entities = new ArrayList<>();
        for (Object rawEntity : Json.asArray(b.get("entities"))) entities.add(parseEntity(Json.asObject(rawEntity)));

        List<RelationshipDef> relationships = new ArrayList<>();
        for (Object rawRel : Json.asArray(b.get("relationships"))) {
            Map<String,Object> r = Json.asObject(rawRel);
            relationships.add(new RelationshipDef(
                Json.text(r.get("sourceEntity")), Json.text(r.get("field")), Json.text(r.get("targetEntity")),
                Json.text(r.get("onDelete")), Json.text(r.get("scope"))
            ));
        }

        List<WorkflowDef> workflows = new ArrayList<>();
        for (Object rawWf : Json.asArray(b.get("workflows"))) workflows.add(parseWorkflow(Json.asObject(rawWf)));

        List<String> allowedThemes = new ArrayList<>();
        for (Object t : Json.asArray(b.get("allowedThemes"))) allowedThemes.add(Json.text(t));

        return new BusinessNeuron(
            Json.text(b.get("userId")), Json.text(b.get("person")), Json.text(b.get("applicationId")),
            Json.text(b.get("business")), Json.text(b.get("language")), Json.text(b.get("locale")),
            Json.text(b.get("defaultTheme")), List.copyOf(allowedThemes),
            List.copyOf(entities), List.copyOf(relationships), List.copyOf(workflows),
            Json.text(b.get("persistencePartition")), Json.text(b.get("administrationScope"))
        );
    }

    private static EntityDef parseEntity(Map<String,Object> e) {
        List<FieldDef> fields = new ArrayList<>();
        for (Object rawField : Json.asArray(e.get("fields"))) fields.add(parseField(Json.asObject(rawField)));
        return new EntityDef(Json.text(e.get("id")), Json.text(e.get("name")),
            Json.text(e.get("displayField")), Json.text(e.get("secondaryDisplayField")), List.copyOf(fields));
    }

    private static FieldDef parseField(Map<String,Object> f) {
        List<String> enumValues = new ArrayList<>();
        for (Object v : Json.asArray(f.get("enumValues"))) enumValues.add(Json.text(v));

        Map<String,String> localizedEnumLabels = new LinkedHashMap<>();
        for (var entry : Json.asObject(f.get("localizedEnumLabels")).entrySet()) {
            localizedEnumLabels.put(entry.getKey(), Json.text(entry.getValue()));
        }

        String referenceEntity = Json.text(f.get("referenceEntity"));
        return new FieldDef(
            Json.text(f.get("id")), Json.text(f.get("label")),
            BusinessModel.FieldType.valueOf(Json.text(f.get("type"))),
            Boolean.TRUE.equals(f.get("required")), Boolean.TRUE.equals(f.get("editable")),
            Boolean.TRUE.equals(f.get("generated")), List.copyOf(enumValues), Map.copyOf(localizedEnumLabels),
            referenceEntity == null ? "" : referenceEntity
        );
    }

    private static WorkflowDef parseWorkflow(Map<String,Object> w) {
        List<TransitionDef> transitions = new ArrayList<>();
        for (Object rawT : Json.asArray(w.get("transitions"))) {
            Map<String,Object> t = Json.asObject(rawT);
            Map<String,String> labels = new LinkedHashMap<>();
            for (var entry : Json.asObject(t.get("localizedActionLabel")).entrySet()) {
                labels.put(entry.getKey(), Json.text(entry.getValue()));
            }
            transitions.add(new TransitionDef(Json.text(t.get("from")), Json.text(t.get("to")), Map.copyOf(labels)));
        }
        return new WorkflowDef(Json.text(w.get("id")), Json.text(w.get("subjectEntity")), Json.text(w.get("stateField")),
            List.copyOf(transitions), Json.text(w.get("authority")), Json.text(w.get("uiTrigger")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> loadResource() {
        try (InputStream in = Businesses.class.getResourceAsStream("/schema/businesses.json")) {
            if (in == null) throw new IllegalStateException("Missing bundled resource: /schema/businesses.json");
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Json.asObject(Json.parse(raw));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fail fast if the bundled resource ever drifts from the spec's own declared counts
     * (13 entities, 40 fields, 7 relationships, 7 workflows across 4 businesses - independently
     * re-derived from the decoded AST, not merely copied from its self-reported semanticValidation). */
    private static void verifyCountsMatchSpec() {
        int businessCount = ALL.size();
        int entityCount = ALL.stream().mapToInt(b -> b.entities().size()).sum();
        int fieldCount = ALL.stream().flatMap(b -> b.entities().stream()).mapToInt(e -> e.fields().size()).sum();
        int relationshipCount = ALL.stream().mapToInt(b -> b.relationships().size()).sum();
        int workflowCount = ALL.stream().mapToInt(b -> b.workflows().size()).sum();
        if (businessCount != 4 || entityCount != 13 || fieldCount != 40 || relationshipCount != 7 || workflowCount != 7) {
            throw new IllegalStateException(String.format(
                "Bundled schema resource does not match the specification's declared shape: "
                + "businesses=%d(expected 4) entities=%d(expected 13) fields=%d(expected 40) "
                + "relationships=%d(expected 7) workflows=%d(expected 7)",
                businessCount, entityCount, fieldCount, relationshipCount, workflowCount));
        }
    }
}

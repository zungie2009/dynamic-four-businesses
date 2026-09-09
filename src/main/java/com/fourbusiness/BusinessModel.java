package com.fourbusiness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Typed shapes for the specification decoded from {@code fourbusinesscompressedscript4.jsonc}
 * (format {@code LLM_AST_TOKEN_MANIFEST_V4}, source {@code RECURSIVE_FOUR_BUSINESS_HIERARCHY_V4}).
 * Populated by {@link Businesses}, which loads {@code /schema/businesses.json} - a lossless,
 * script-generated extract of the decoded AST (see README) - rather than hand-transcribing
 * 40 fields' worth of localized labels into Java source, which is how the previous version of
 * this app (built from V2 of the script) did it and which is far more error-prone at this size.
 */
public final class BusinessModel {
    private BusinessModel() {}

    public enum FieldType { STRING, EMAIL, ENUM, DECIMAL_19_2, REFERENCE, INTEGER, ISO_DATE }

    /**
     * A field as declared. {@code localizedEnumLabels} carries this business's own single
     * language's display text per canonical enum value (each business is monolingual - see
     * {@code uiNeuron.enumDisplay: BUSINESS_LOCALE_LABEL}); {@code enumStorage:
     * CANONICAL_UPPERCASE_VALUE} means the canonical value itself is always what's persisted
     * and validated against, never the localized label. {@code editable}/{@code generated} are
     * carried through from the spec but not independently enforced beyond what
     * {@link RecordValidator} already does for required/type/workflow-governed fields - no
     * field in this spec declares {@code editable:false} except administration's own read-only
     * fields (a separate, smaller field list - see {@link Businesses#administrationFields()}).
     */
    public record FieldDef(String id, String label, FieldType type, boolean required, boolean editable,
                            boolean generated, List<String> enumValues, Map<String,String> localizedEnumLabels,
                            String referenceEntity) {
        /** {@code uiNeuron.fieldTypeControlMap} - derived from type, not stored per field, because
         * independent verification found every one of the spec's 40 fields' declared {@code uiControl}
         * already equal to this map applied to that field's {@code type} (see README). */
        public String uiControl() { return UiNeuron.CONTROL_FOR_TYPE.get(type); }

        /** REFERENCE fields are scoped {@code ACTIVE_BUSINESS_PARTITION_ONLY}; every other field
         * is {@code NOT_APPLICABLE} - likewise derived, and likewise verified universal. */
        public String pickerScope() { return type == FieldType.REFERENCE ? "ACTIVE_BUSINESS_PARTITION_ONLY" : "NOT_APPLICABLE"; }
    }

    public record EntityDef(String id, String name, String displayField, String secondaryDisplayField, List<FieldDef> fields) {
        public Optional<FieldDef> field(String fieldId) {
            return fields.stream().filter(f -> f.id().equals(fieldId)).findFirst();
        }
    }

    /** {@code onDelete} is always {@code RESTRICT} and {@code scope} always
     * {@code SAME_BUSINESS_PARTITION_ONLY} in this specification. */
    public record RelationshipDef(String sourceEntity, String field, String targetEntity, String onDelete, String scope) {}

    public record TransitionDef(String from, String to, Map<String,String> localizedActionLabel) {}

    /**
     * A declared state machine. {@code uiTrigger: ROW_ACTION_BUTTONS_FOR_ALLOWED_TRANSITIONS} on
     * every workflow means the client renders one button per transition currently valid from
     * the record's current state, labeled with that transition's own
     * {@code localizedActionLabel} for the active locale - not a generic "change status" control.
     */
    public record WorkflowDef(String id, String subjectEntity, String stateField,
                               List<TransitionDef> transitions, String authority, String uiTrigger) {
        /** The one enum value that never appears as a transition target - not declared explicitly
         * anywhere in the spec, but independently confirmed to hold for all 7 workflows (see README). */
        public String initialState(List<String> enumValues) {
            Set<String> targets = transitions.stream().map(TransitionDef::to).collect(Collectors.toSet());
            return enumValues.stream().filter(v -> !targets.contains(v)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Workflow " + id + " has no derivable initial state"));
        }
        public boolean allows(String from, String to) {
            return transitions.stream().anyMatch(t -> t.from().equals(from) && t.to().equals(to));
        }
        public Optional<TransitionDef> transition(String from, String to) {
            return transitions.stream().filter(t -> t.from().equals(from) && t.to().equals(to)).findFirst();
        }
    }

    public record BusinessNeuron(String userId, String person, String applicationId, String businessName,
                                  String language, String locale, String defaultTheme, List<String> allowedThemes,
                                  List<EntityDef> entities, List<RelationshipDef> relationships,
                                  List<WorkflowDef> workflows, String persistencePartition, String administrationScope) {

        public Optional<EntityDef> entity(String entityId) {
            return entities.stream().filter(e -> e.id().equals(entityId)).findFirst();
        }

        /** The workflow governing this entity's state field, if any - Sofia's Propiedad.Estado
         * (properties.status) is the one declared enum field in this spec with no governing
         * workflow, preserved as a plain settable enum rather than given an invented state machine. */
        public Optional<WorkflowDef> workflowFor(String entityId) {
            return workflows.stream().filter(w -> w.subjectEntity().equals(entityId)).findFirst();
        }

        public List<RelationshipDef> relationshipsTargeting(String entityId) {
            return relationships.stream().filter(r -> r.targetEntity().equals(entityId)).collect(Collectors.toList());
        }

        /** Compound storage kind: (businessAppId, entityId) - keeps e.g. Marie's and Hans's
         * both-named "orders" apart; see {@code persistenceNeuron.keyRule}. Joined with "__"
         * rather than "::" because {@link Store} uses this string directly as a filesystem
         * directory name (via {@code Path.resolve}), and ':' is illegal in a Windows path
         * component - "::" crashed with an InvalidPathException there on first boot even
         * though it worked fine on Linux/macOS. */
        public String storageKind(String entityId) {
            return applicationId + "__" + entityId;
        }

        public String administrationKind() { return applicationId + "___administration"; }
        public String auditKind() { return applicationId + "___audit"; }
        public String seedMarkerKind() { return applicationId + "___seed"; }
    }
}

package com.fourbusiness;

import com.fourbusiness.BusinessModel.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generic, schema-driven validation engine: the same code validates every entity of every
 * business by reading its declared {@link BusinessModel.FieldDef} list. Canonical enum values
 * are what's validated and stored ({@code uiNeuron.enumStorage: CANONICAL_UPPERCASE_VALUE});
 * {@code localizedEnumLabels} is display-only and never affects validation.
 */
public final class RecordValidator {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final Store store;

    public RecordValidator(Store store) {
        this.store = store;
    }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }

    /**
     * Validates and returns a persist-ready record for {@code entity} within {@code business}.
     * A field governed by a workflow (see {@link BusinessModel.BusinessNeuron#workflowFor}) is
     * handled specially: on create it is always set to the workflow's derived initial state
     * (client-supplied value ignored - {@code uiNeuron.unrestrictedStatusEditing: false} means a
     * record can never be created or edited directly into an arbitrary state); on update it may
     * not be changed here at all - state changes only through
     * {@link FourBusinessApplication}'s declared transition route. An ungoverned enum field
     * (Sofia's Propiedad.Estado) has none of this special handling and is a plain required enum.
     */
    public Map<String,Object> normalize(BusinessModel.BusinessNeuron business, EntityDef entity,
                                         Map<String,Object> incoming, Map<String,Object> existing, String existingId) {
        var workflow = business.workflowFor(entity.id());
        Map<String,Object> merged = new LinkedHashMap<>();
        if (existing != null) merged.putAll(existing);
        if (incoming != null) merged.putAll(incoming);
        merged.remove("id");

        Map<String,Object> normalized = new LinkedHashMap<>();
        for (FieldDef f : entity.fields()) {
            boolean isWorkflowState = workflow.isPresent() && workflow.get().stateField().equals(f.id());

            if (isWorkflowState) {
                if (existing == null) {
                    normalized.put(f.id(), workflow.get().initialState(f.enumValues()));
                } else {
                    Object requested = incoming == null ? null : incoming.get(f.id());
                    Object current = existing.get(f.id());
                    if (requested != null && !String.valueOf(requested).equals(String.valueOf(current))) {
                        throw new ValidationException("'" + f.id() + "' cannot be changed directly - use the "
                            + workflow.get().id() + " transition route");
                    }
                    normalized.put(f.id(), current);
                }
                continue;
            }

            if (!f.editable() && existing != null) {
                // No declared field in this spec is currently non-editable, but honored for fidelity
                // to the contract rather than assumed away.
                normalized.put(f.id(), existing.get(f.id()));
                continue;
            }

            Object raw = merged.get(f.id());
            String text = raw == null ? null : String.valueOf(raw).trim();

            if (f.required() && (text == null || text.isEmpty())) {
                throw new ValidationException("'" + f.id() + "' is required");
            }
            if (text == null || text.isEmpty()) continue;

            switch (f.type()) {
                case STRING -> normalized.put(f.id(), text);
                case EMAIL -> {
                    if (!EMAIL.matcher(text).matches()) throw new ValidationException("'" + f.id() + "' must be a valid email address");
                    normalized.put(f.id(), text);
                }
                case INTEGER -> {
                    try { normalized.put(f.id(), Long.parseLong(text)); }
                    catch (NumberFormatException e) { throw new ValidationException("'" + f.id() + "' must be a whole number"); }
                }
                case DECIMAL_19_2 -> {
                    BigDecimal amount;
                    try { amount = raw instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : new BigDecimal(text); }
                    catch (NumberFormatException e) { throw new ValidationException("'" + f.id() + "' must be a decimal number"); }
                    normalized.put(f.id(), amount.setScale(2, RoundingMode.HALF_UP));
                }
                case ISO_DATE -> {
                    try { LocalDate.parse(text); }
                    catch (DateTimeParseException e) { throw new ValidationException("'" + f.id() + "' must be an ISO-8601 date (YYYY-MM-DD)"); }
                    normalized.put(f.id(), text);
                }
                case ENUM -> {
                    String upper = text.toUpperCase(Locale.ROOT);
                    if (!f.enumValues().contains(upper)) throw new ValidationException("'" + f.id() + "' must be one of " + f.enumValues());
                    normalized.put(f.id(), upper);
                }
                case REFERENCE -> {
                    // Resolved within this business's own partition only - never across businesses.
                    if (store.get(business.storageKind(f.referenceEntity()), text) == null) {
                        throw new ValidationException("'" + f.id() + "' does not reference an existing "
                            + f.referenceEntity() + " record in " + business.businessName() + " (" + text + ")");
                    }
                    normalized.put(f.id(), text);
                }
            }
        }
        return normalized;
    }

    /** onDelete RESTRICT, scoped strictly within {@code business}'s own partition. */
    public void checkDeleteAllowed(BusinessModel.BusinessNeuron business, String entityId, String recordId) {
        for (RelationshipDef rel : business.relationshipsTargeting(entityId)) {
            for (Map<String,Object> candidate : store.all(business.storageKind(rel.sourceEntity()))) {
                if (recordId.equals(String.valueOf(candidate.get(rel.field())))) {
                    EntityDef sourceEntity = business.entity(rel.sourceEntity()).orElseThrow();
                    throw new ValidationException("Cannot delete: " + sourceEntity.name() + " " + candidate.get("id")
                        + " still references this record via '" + rel.field() + "'");
                }
            }
        }
    }
}

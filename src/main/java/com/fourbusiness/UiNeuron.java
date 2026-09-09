package com.fourbusiness;

import java.util.Map;

import static com.fourbusiness.BusinessModel.FieldType.*;

/**
 * The declared {@code uiNeuron} constants, transcribed literally from the decoded script:
 * {@code fieldTypeControlMap} (verified to match every one of the spec's 40 fields'
 * declared {@code uiControl} with zero exceptions - see README), plus the small set of
 * global UI/storage rules the client and server both need to honor.
 */
public final class UiNeuron {
    private UiNeuron() {}

    public static final Map<BusinessModel.FieldType, String> CONTROL_FOR_TYPE = Map.of(
        STRING, "TEXT_INPUT",
        EMAIL, "EMAIL_INPUT",
        ENUM, "LOCALIZED_SELECT",
        INTEGER, "INTEGER_INPUT",
        ISO_DATE, "DATE_INPUT",
        DECIMAL_19_2, "DECIMAL_INPUT",
        REFERENCE, "SAME_PARTITION_ENTITY_PICKER"
    );

    /** {@code stateTransitionControl}: the client renders one explicit button per currently
     * allowed transition rather than a free-form status editor. */
    public static final String STATE_TRANSITION_CONTROL = "EXPLICIT_ALLOWED_TRANSITION_BUTTONS";
    /** {@code enumDisplay}: localized label shown; {@code enumStorage}: canonical value persisted. */
    public static final String ENUM_DISPLAY = "BUSINESS_LOCALE_LABEL";
    public static final String ENUM_STORAGE = "CANONICAL_UPPERCASE_VALUE";
    /** Switching the active user replaces the projected business entirely rather than merging state. */
    public static final String PROJECTION_RULE = "REPLACE_NOT_MERGE";
    /** A workflow-governed state field can never be set outside its declared transitions. */
    public static final boolean UNRESTRICTED_STATUS_EDITING = false;
}

package dev.ngb.survey.authoring.api.document;

/**
 * The closed list of codes a draft or a version fails at. It is part of the contract: a builder UI branches on these
 * names, so a new reason is a new version of the contract, never a free-form string.
 */
public enum ValidationCode {
    /** A member the version must carry is absent. */
    REQUIRED("required"),
    /** A value or a member the version does not define: an unknown question type, a malformed id, an extra member. */
    UNKNOWN_TYPE("unknown_type"),
    /** Two structural nodes share an id, which would make every reference to it ambiguous. */
    DUPLICATE_ID("duplicate_id"),
    /** A jump, a translation key or an expression names something the version does not contain. */
    DANGLING_REFERENCE("dangling_reference"),
    /** An enabled locale is missing a key the version needs. */
    TRANSLATION_MISSING("translation_missing"),
    /** A limit the schema or the expression budget sets was exceeded. */
    LIMIT_EXCEEDED("limit_exceeded"),
    /** Following the jumps would revisit a page forever. */
    CYCLE("cycle"),
    /** An expression is not valid: an unknown operator, the wrong arity or a banned reference. */
    EXPRESSION_INVALID("expression_invalid");

    private final String wireName;

    ValidationCode(String wireName) {
        this.wireName = wireName;
    }

    /** The name on the wire, which is the contract's spelling rather than the enum constant. */
    public String wireName() {
        return wireName;
    }
}

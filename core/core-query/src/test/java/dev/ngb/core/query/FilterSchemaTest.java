package dev.ngb.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FilterSchemaTest {

    @Test
    void aNullableFieldCannotBeSortable() {
        assertThatThrownBy(() -> FieldSpec.of("completed_at", FieldType.INSTANT).asNullable().asSortable())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullable");
    }

    @Test
    void aRelatedFieldCannotBeSortable() {
        assertThatThrownBy(() -> FieldSpec.of("wallet.customer_id", FieldType.UUID).asSortable())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("related");
    }

    @Test
    void textOperatorsApplyOnlyToText() {
        assertThatThrownBy(() -> FieldSpec.of("amount", FieldType.LONG).with(Op.CONTAINS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldSpec.of("amount", FieldType.LONG).with(Op.STARTS_WITH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderingOperatorsNeedAnOrderedType() {
        assertThatThrownBy(() -> FieldSpec.of("name", FieldType.STRING).with(Op.GT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldSpec.of("status", FieldType.BOOLEAN).with(Op.LT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isNullIsOfferedOnlyToANullableField() {
        assertThat(FieldSpec.of("reference", FieldType.STRING).ops()).doesNotContain(Op.IS_NULL);
        assertThat(FieldSpec.of("reference", FieldType.STRING).asNullable().ops()).contains(Op.IS_NULL);
        assertThatThrownBy(() -> FieldSpec.of("reference", FieldType.STRING).only(Op.IS_NULL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEnumNeedsValuesAndOnlyAnEnumHasThem() {
        assertThatThrownBy(() -> FieldSpec.of("status", FieldType.ENUM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FieldSpec("name", FieldType.STRING, java.util.Set.of("A"), java.util.Set.of(Op.EQ), false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFieldNameIsLowerSnakeCaseWithAtMostOneDot() {
        assertThatThrownBy(() -> FieldSpec.of("createdAt", FieldType.INSTANT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldSpec.of("a.b.c", FieldType.UUID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTieBreakerMustBeADeclaredSortableField() {
        assertThatThrownBy(() -> FilterSchema.of("id", "-id", FieldSpec.of("id", FieldType.UUID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterSchema.of("missing", "-id", FieldSpec.of("id", FieldType.UUID).asSortable()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFieldIsDeclaredOnce() {
        assertThatThrownBy(() -> FilterSchema.of("id", "-id", FieldSpec.of("id", FieldType.UUID).asSortable(), FieldSpec.of("id", FieldType.UUID)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultSortIsDeclaredAndMustNameSortableFields() {
        FieldSpec id = FieldSpec.of("id", FieldType.UUID).asSortable();
        assertThatThrownBy(() -> FilterSchema.of("id", " ", id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterSchema.of("id", "-nope", id)).hasMessageContaining("cannot be sorted");
    }
}

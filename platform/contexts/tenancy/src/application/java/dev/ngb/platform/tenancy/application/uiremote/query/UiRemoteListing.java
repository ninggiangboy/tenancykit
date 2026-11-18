package dev.ngb.platform.tenancy.application.uiremote.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;

/**
 * What a Platform administrator may filter and sort remotes on. {@code name} offers {@code contains} because that is how
 * the screen is searched and the column carries a trigram index; navigation order is the default because it is the order
 * the sidebar shows.
 */
public final class UiRemoteListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("name", "nav_order",
            FieldSpec.of("name", FieldType.STRING).asSortable().with(Op.CONTAINS),
            FieldSpec.of("app_code", FieldType.STRING),
            FieldSpec.of("nav_order", FieldType.LONG).asSortable(),
            FieldSpec.of("enabled", FieldType.BOOLEAN),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("version", FieldType.LONG));

    private UiRemoteListing() {}
}

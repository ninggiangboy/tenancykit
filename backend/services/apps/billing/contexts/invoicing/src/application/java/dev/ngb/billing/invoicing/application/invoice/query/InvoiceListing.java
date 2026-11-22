package dev.ngb.billing.invoicing.application.invoice.query;

import dev.ngb.billing.invoicing.domain.invoice.InvoiceOrigin;
import dev.ngb.billing.invoicing.domain.invoice.InvoiceStatus;
import dev.ngb.billing.invoicing.domain.invoice.Settlement;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;

/** What a caller may filter and sort invoices on. Newest first by default; invoice ids are time-ordered. */
public final class InvoiceListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("customer_id", FieldType.UUID),
            FieldSpec.of("wallet_id", FieldType.UUID),
            FieldSpec.of("currency", FieldType.STRING).only(Op.EQ, Op.NEQ, Op.IN, Op.NIN),
            FieldSpec.of("number", FieldType.STRING).asNullable(),
            FieldSpec.enumOf("origin", InvoiceOrigin.class),
            FieldSpec.enumOf("status", InvoiceStatus.class),
            FieldSpec.of("total", FieldType.LONG).asSortable(),
            FieldSpec.of("period_start", FieldType.INSTANT).asNullable(),
            FieldSpec.of("period_end", FieldType.INSTANT).asNullable(),
            FieldSpec.enumOf("settlement", Settlement.class).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("issued_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("paid_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("voided_at", FieldType.INSTANT).asNullable());

    private InvoiceListing() {}
}

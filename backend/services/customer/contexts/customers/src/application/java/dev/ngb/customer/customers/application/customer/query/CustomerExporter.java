package dev.ngb.customer.customers.application.customer.query;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.customer.customers.application.customer.query.ExportLease;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Streams the customer list as CSV: the attributes the Tenant itself sent, its tags pipe-separated, and one column per
 * active field key.
 *
 * <p>The rows are read through a server-side cursor and written as they arrive, so an export of a hundred thousand
 * customers holds one page of them rather than all of them. The query is built before the first byte is written, so a
 * filter the Tenant cannot apply is a {@code 400} rather than half a file.
 *
 * <p>One export runs per tenant at a time, held by a short lease: a second request is refused with {@code 429} instead
 * of reading the same rows twice.
 */
public class CustomerExporter {

    /** The largest number of rows one round trip may read, so a caller cannot ask for the whole table at once. */
    public static final int MAX_PAGE_SIZE = 10_000;

    /** The header's fixed columns, before the one column per active field key. */
    private static final List<String> FIXED = List.of("externalRef", "displayName", "phone", "email", "tags");

    private final UnitOfWork unitOfWork;
    private final CustomerExportReadRepository customers;
    private final ExportLease leases;
    private final int defaultPageSize;

    public CustomerExporter(
            UnitOfWork unitOfWork,
            CustomerExportReadRepository customers,
            ExportLease leases,
            int defaultPageSize) {
        this.unitOfWork = unitOfWork;
        this.customers = customers;
        this.leases = leases;
        this.defaultPageSize = defaultPageSize;
    }

    /**
     * Writes the matching customers as CSV to {@code out}.
     *
     * @param pageSize how many rows one round trip reads, 1 to {@value #MAX_PAGE_SIZE}; null means the setting
     */
    public void export(CustomerSearchCriteria criteria, Integer pageSize, OutputStream out) {
        int size = pageSize(pageSize);
        ExportLease.Hold hold = leases.acquire();
        try {
            unitOfWork.transactional(() -> write(criteria, size, out));
        } finally {
            hold.close();
        }
    }

    /** Reads and writes one page at a time, inside the transaction that binds the tenant. */
    private Void write(CustomerSearchCriteria criteria, int pageSize, OutputStream out) {
        CustomerExportReadRepository.Prepared prepared = customers.prepare(criteria, pageSize);
        Writer sink = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        CsvText csv = new CsvText(sink);
        try {
            csv.row(header(prepared.fieldKeys()));
            prepared.rows().forEach(row -> {
                try {
                    csv.row(cells(row, prepared.fieldKeys()));
                } catch (IOException e) {
                    throw new UncheckedIOException("The export stream could not be written.", e);
                }
            });
            csv.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("The export stream could not be written.", e);
        }
        return null;
    }

    private static List<String> header(List<String> fieldKeys) {
        List<String> header = new ArrayList<>(FIXED.size() + fieldKeys.size() + 1);
        header.addAll(FIXED);
        header.addAll(fieldKeys);
        header.add("createdAt");
        return List.copyOf(header);
    }

    private static List<String> cells(CustomerExportRow row, List<String> fieldKeys) {
        List<String> cells = new ArrayList<>(FIXED.size() + fieldKeys.size() + 1);
        cells.add(row.externalRef());
        cells.add(row.displayName());
        cells.add(row.phone());
        cells.add(row.email());
        cells.add(String.join("|", row.tags()));
        for (String key : fieldKeys) {
            cells.add(CsvText.value(row.customFields().get(key)));
        }
        cells.add(row.createdAt() == null ? "" : row.createdAt().toString());
        return cells;
    }

    private int pageSize(Integer asked) {
        if (asked == null) {
            return defaultPageSize;
        }
        if (asked < 1 || asked > MAX_PAGE_SIZE) {
            throw new BadRequestException("An export page size is 1 to " + MAX_PAGE_SIZE + ".");
        }
        return asked;
    }
}

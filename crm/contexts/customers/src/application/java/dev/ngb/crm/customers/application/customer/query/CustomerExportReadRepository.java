package dev.ngb.crm.customers.application.customer.query;

import java.util.List;
import java.util.function.Consumer;

/**
 * Reads the customers an export writes, one row at a time, through a server-side cursor so the whole table never sits
 * in memory.
 *
 * <p>The query is built and checked in {@link #prepare}, before the caller writes a byte: a filter the Tenant cannot
 * apply is refused as a request error rather than discovered half way through a stream.
 */
public interface CustomerExportReadRepository {

    /**
     * Prepares the stream: the active field keys that become columns, and the rows in the exported order.
     *
     * @param pageSize how many rows one round trip to the database reads
     */
    Prepared prepare(CustomerSearchCriteria criteria, int pageSize);

    /** A stream ready to be read. */
    record Prepared(List<String> fieldKeys, RowSource rows) {

        public Prepared {
            fieldKeys = List.copyOf(fieldKeys);
        }
    }

    /** Reads the prepared rows one at a time, holding no more than one page of them. */
    interface RowSource {

        void forEach(Consumer<CustomerExportRow> sink);
    }
}

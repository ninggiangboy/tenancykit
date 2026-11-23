package dev.ngb.customer.customers.adapter.customer.web;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.customer.customers.application.customer.query.CustomerExporter;
import dev.ngb.customer.shared.CustomerPermissions;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /customers/export}: the customer list as a streamed CSV file.
 *
 * <p>It takes the same filters as the list, so an export is the list written down rather than another query with its
 * own rules. The file is streamed as it is read, so a large export uses a fixed amount of memory, and one export runs
 * per tenant at a time: a second request while the first is streaming is refused with {@code 429}.
 */
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class ExportController {

    /** What a CSV response is; a spreadsheet and a script both read it without being told twice. */
    private static final String CSV = "text/csv";

    private final CustomerExporter export;

    public ExportController(CustomerExporter export) {
        this.export = export;
    }

    /** Streams the matching customers, newest first. One column per active field key follows the fixed ones. */
    @GetMapping("/customers/export")
    @RequiresPermission(CustomerPermissions.CUSTOMER_EXPORT)
    public void exportCustomers(
            @RequestParam MultiValueMap<String, String> params, HttpServletResponse response) throws IOException {
        CustomerSearchQuery.Parsed parsed = CustomerSearchQuery.parse(params, "page_size");
        Integer pageSize = pageSize(params);
        response.setContentType(CSV);
        response.setCharacterEncoding("UTF-8");
        export.export(parsed.criteria(), pageSize, response.getOutputStream());
        response.flushBuffer();
    }

    private static Integer pageSize(MultiValueMap<String, String> params) {
        String raw = params.getFirst("page_size");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.strip());
        } catch (NumberFormatException notANumber) {
            throw new BadRequestException("A page size is a whole number.");
        }
    }
}

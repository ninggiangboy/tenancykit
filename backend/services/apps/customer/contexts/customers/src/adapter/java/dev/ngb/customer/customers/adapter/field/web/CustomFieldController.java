package dev.ngb.customer.customers.adapter.field.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.customer.customers.application.field.command.CreateFieldDefinitionUseCase;
import dev.ngb.customer.customers.application.field.command.PatchFieldDefinitionUseCase;
import dev.ngb.customer.customers.application.field.query.FieldDefinitionListing;
import dev.ngb.customer.customers.application.field.query.FieldDefinitionQueries;
import dev.ngb.customer.customers.application.field.query.FieldDefinitionView;
import dev.ngb.customer.customers.domain.field.FieldDefinition;
import dev.ngb.customer.shared.CustomerPermissions;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant's own customer attributes: the definitions its values are written under.
 *
 * <p>A definition is created with its key and its type and keeps both for as long as it exists, so a value written
 * once keeps its meaning. What changes is the label, the choices a {@code CHOICE} accepts and whether it is archived;
 * an archived definition is still read back here, and no new value may be written for it.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class CustomFieldController {

    /** A definition the Tenant defines: the key and the type are chosen once. */
    public record CustomFieldRequest(String key, String label, String type, List<String> choices) {}

    /** The properties to change: a null one is left as it is. A type other than the stored one is refused. */
    public record CustomFieldPatch(String label, List<String> choices, Boolean archived, String type) {}

    public record CustomFieldResponse(
            String key, String label, String type, List<String> choices, boolean archived, Instant createdAt) {

        static CustomFieldResponse of(FieldDefinition definition) {
            return new CustomFieldResponse(
                    definition.key(),
                    definition.label(),
                    definition.type().name(),
                    definition.choices(),
                    definition.archived(),
                    definition.createdAt());
        }

        static CustomFieldResponse ofView(FieldDefinitionView view) {
            return new CustomFieldResponse(
                    view.key(), view.label(), view.type().name(), view.choices(), view.archived(), view.createdAt());
        }
    }

    private final CreateFieldDefinitionUseCase create;
    private final PatchFieldDefinitionUseCase patches;
    private final FieldDefinitionQueries queries;

    public CustomFieldController(
            CreateFieldDefinitionUseCase create, PatchFieldDefinitionUseCase patches, FieldDefinitionQueries queries) {
        this.create = create;
        this.patches = patches;
        this.queries = queries;
    }

    @GetMapping("/custom-fields")
    @Listing(FieldDefinitionListing.class)
    @RequiresPermission(CustomerPermissions.FIELD_MANAGE)
    public Page<CustomFieldResponse> listCustomFields(ListQuery query) {
        return queries.list(query).map(CustomFieldResponse::ofView);
    }

    @PostMapping("/custom-fields")
    @RequiresPermission(CustomerPermissions.FIELD_MANAGE)
    public ResponseEntity<CustomFieldResponse> createCustomField(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody CustomFieldRequest request) {
        FieldDefinition definition = create.execute(new CreateFieldDefinitionUseCase.Command(
                request.key(), request.label(), request.type(), request.choices()));
        return ResponseEntity.status(HttpStatus.CREATED).body(CustomFieldResponse.of(definition));
    }

    @PatchMapping("/custom-fields/{key}")
    @RequiresPermission(CustomerPermissions.FIELD_MANAGE)
    public CustomFieldResponse patchCustomField(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody CustomFieldPatch request) {
        FieldDefinition definition = patches.execute(
                key,
                new PatchFieldDefinitionUseCase.Change(
                        request.label(), request.choices(), request.archived(), request.type()));
        return CustomFieldResponse.of(definition);
    }
}

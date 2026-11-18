package dev.ngb.workflow.connections.application.credential.port;

/** The token a Tenant may send to one app, cached until shortly before it expires. */
public interface TokenProvider {

    String token(dev.ngb.core.id.TenantId tenant, String audience);
}

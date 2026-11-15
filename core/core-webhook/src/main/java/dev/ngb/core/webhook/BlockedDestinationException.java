package dev.ngb.core.webhook;

import java.net.UnknownHostException;

/** The host resolves to an address a webhook must not be sent to. */
public final class BlockedDestinationException extends UnknownHostException {

    private static final long serialVersionUID = 1L;

    public BlockedDestinationException(String host) {
        super("blocked destination: " + host);
    }
}

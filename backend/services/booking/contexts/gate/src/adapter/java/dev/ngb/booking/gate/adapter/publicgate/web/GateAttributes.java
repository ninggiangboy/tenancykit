package dev.ngb.booking.gate.adapter.publicgate.web;

import dev.ngb.booking.gate.domain.token.CustomerClaims;

/** The request attribute the gate filter writes and the public controller reads. */
public final class GateAttributes {

    public static final String CLAIMS = "booking.gate.claims";

    private GateAttributes() {}

    public static CustomerClaims require(jakarta.servlet.http.HttpServletRequest request) {
        Object claims = request.getAttribute(CLAIMS);
        if (claims instanceof CustomerClaims customer) {
            return customer;
        }
        throw new IllegalStateException("the public gate filter did not run");
    }
}

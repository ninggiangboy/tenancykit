package dev.ngb.keycloak;

import java.io.IOException;

/** Where a finished command goes; the broker publisher in the server, a recording stub in tests. */
interface MailerSink {

    void publish(MailerCommand command) throws IOException;
}

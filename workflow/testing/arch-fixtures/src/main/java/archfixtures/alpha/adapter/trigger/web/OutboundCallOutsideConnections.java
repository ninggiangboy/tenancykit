package archfixtures.alpha.adapter.trigger.web;

import java.net.http.HttpClient;

/** A fixture that opens its own outbound connection outside the connections context. */
public class OutboundCallOutsideConnections {

    public HttpClient client() {
        return HttpClient.newHttpClient();
    }
}

package dev.ngb.crm.activity.application.activity.service;

import java.util.Map;

/**
 * How a writer's payload crosses into the JSON object it is stored as.
 *
 * <p>The encoding is canonical: the same payload always gives the same text, so a stored activity and a sent body that
 * say the same thing compare equal, which is what makes a repeated request a replay. The byte length is the size the
 * payload bound is measured in, so what is refused and what is stored are the same bytes.
 */
public interface ActivityPayloads {

    String toJson(Map<String, Object> payload);

    Map<String, Object> fromJson(String json);

    int byteLength(Map<String, Object> payload);
}

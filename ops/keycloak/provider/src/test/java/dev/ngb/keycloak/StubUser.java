package dev.ngb.keycloak;

import java.lang.reflect.Proxy;
import java.util.Map;
import org.keycloak.models.UserModel;

/** A user that answers only what the sender asks: its id, email and attributes. */
final class StubUser {

    private StubUser() {}

    static UserModel of(String email, Map<String, String> attributes) {
        return (UserModel) Proxy.newProxyInstance(
                UserModel.class.getClassLoader(), new Class<?>[] {UserModel.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getId" -> "user-1";
                            case "getEmail" -> email;
                            case "getFirstAttribute" -> attributes.get((String) args[0]);
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }
}

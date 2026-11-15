package dev.ngb.tenant.identity.adapter.session.web;

import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.tenant.identity.adapter.user.web.UserResponse;
import dev.ngb.tenant.identity.application.session.command.AuthenticateUseCase;
import dev.ngb.tenant.identity.application.user.command.RegisterUserUseCase;
import dev.ngb.tenant.identity.application.session.service.SessionService;
import dev.ngb.tenant.identity.domain.user.User;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Registration, login and logout with this system's own credentials. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(String email, String displayName, String password) {}

    public record LoginRequest(String email, String password) {}

    public record LoginResponse(String token, Instant expiresAt, UserResponse user) {}

    private final RegisterUserUseCase register;
    private final AuthenticateUseCase authenticate;
    private final SessionService sessions;

    public AuthController(RegisterUserUseCase register, AuthenticateUseCase authenticate, SessionService sessions) {
        this.register = register;
        this.authenticate = authenticate;
        this.sessions = sessions;
    }

    /** The first account of this tenant becomes its administrator; every later self-registration is a viewer. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @PublicEndpoint
    public UserResponse register(@RequestBody RegisterRequest request) {
        User user = register.execute(new RegisterUserUseCase.Command(
                request.email(), request.displayName(), request.password(), null));
        return UserResponse.of(user);
    }

    @PostMapping("/login")
    @PublicEndpoint
    public LoginResponse login(@RequestBody LoginRequest request) {
        AuthenticateUseCase.Result result = authenticate.execute(request.email(), request.password());
        return new LoginResponse(result.token(), result.expiresAt(), UserResponse.of(result.user()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Authenticated
    public void logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        sessions.logout(bearer(authorization));
    }

    static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }
}

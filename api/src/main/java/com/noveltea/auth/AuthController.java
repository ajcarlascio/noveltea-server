package com.noveltea.auth;

import com.noveltea.auth.AuthService.DeviceInfo;
import com.noveltea.auth.AuthService.Session;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Auth", description = "Registration, login, device pairing, and the devices a session can see.")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record CredentialsRequest(String email, String password, String deviceName, String platform) {}

    public record PairRequest(String code, String deviceName, String platform) {}

    public record RefreshRequest(String refreshToken) {}

    /**
     * The refresh token is returned once and never retrievable again.
     *
     * @param mustChangePassword this token works, and works for exactly one route:
     *     POST /api/v1/account/password. Every other answers 403 password_change_required.
     *     A client seeing this should send the author to a change-password form rather than
     *     to their projects.
     */
    public record SessionResponse(
            UUID userId, UUID deviceId, String accessToken, String refreshToken,
            long expiresIn, boolean mustChangePassword, boolean isAdmin) {
        static SessionResponse of(Session s) {
            return new SessionResponse(
                    s.userId(), s.deviceId(), s.accessToken(), s.refreshToken(),
                    s.expiresInSeconds(), s.mustChangePassword(), s.isAdmin());
        }
    }

    @Operation(
            summary = "Register a new account",
            description = "Public, and OFF unless noveltea.auth.open-registration is set — "
                    + "a self-hosted instance makes accounts through an administrator, not "
                    + "through whoever can reach the address. Closed answers 403 "
                    + "registration_closed. "
                    + "Otherwise this and login are the only ways to obtain a token. "
                    + "Every auth failure elsewhere in this API returns one identical message, "
                    + "so a caller cannot distinguish \"no such account\" from \"wrong password\".",
            security = {})
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse register(@RequestBody CredentialsRequest request) {
        return SessionResponse.of(auth.register(
                request.email(), request.password(), request.deviceName(), request.platform()));
    }

    @Operation(summary = "Log in", description = "Public.", security = {})
    @PostMapping("/auth/login")
    public SessionResponse login(@RequestBody CredentialsRequest request) {
        return SessionResponse.of(auth.login(
                request.email(), request.password(), request.deviceName(), request.platform()));
    }

    @Operation(
            summary = "Rotate a refresh token",
            description = "Public. Refresh tokens rotate on every use and are single-use: a "
                    + "leaked token works at most once, and the legitimate device's next refresh "
                    + "failing is a detectable signal that it was stolen.",
            security = {})
    @PostMapping("/auth/refresh")
    public SessionResponse refresh(@RequestBody RefreshRequest request) {
        return SessionResponse.of(auth.refresh(request.refreshToken()));
    }

    @Operation(
            summary = "Redeem a pairing code",
            description = "Public. Redeems a short code minted by an already-trusted device "
                    + "(see POST /auth/pairing-codes) to onboard a second one.",
            security = {})
    @PostMapping("/auth/pair")
    public SessionResponse pair(@RequestBody PairRequest request) {
        return SessionResponse.of(auth.pair(request.code(), request.deviceName(), request.platform()));
    }

    @PostMapping("/auth/pairing-codes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createPairingCode(@AuthenticationPrincipal CurrentUser user) {
        String code = auth.createPairingCode(user.userId(), user.deviceId());
        return Map.of("code", code, "expiresInSeconds", 600);
    }

    @GetMapping("/devices")
    public List<DeviceInfo> devices(@AuthenticationPrincipal CurrentUser user) {
        return auth.listDevices(user.userId(), user.deviceId());
    }

    @DeleteMapping("/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID deviceId) {
        auth.revokeDevice(user.userId(), deviceId);
    }
}

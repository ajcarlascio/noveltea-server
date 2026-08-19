package com.noveltea.auth;

import com.noveltea.auth.AuthService.DeviceInfo;
import com.noveltea.auth.AuthService.Session;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record CredentialsRequest(String email, String password, String deviceName, String platform) {}

    public record PairRequest(String code, String deviceName, String platform) {}

    public record RefreshRequest(String refreshToken) {}

    /** The refresh token is returned once and never retrievable again. */
    public record SessionResponse(
            UUID userId, UUID deviceId, String accessToken, String refreshToken, long expiresIn) {
        static SessionResponse of(Session s) {
            return new SessionResponse(
                    s.userId(), s.deviceId(), s.accessToken(), s.refreshToken(), s.expiresInSeconds());
        }
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse register(@RequestBody CredentialsRequest request) {
        return SessionResponse.of(auth.register(
                request.email(), request.password(), request.deviceName(), request.platform()));
    }

    @PostMapping("/auth/login")
    public SessionResponse login(@RequestBody CredentialsRequest request) {
        return SessionResponse.of(auth.login(
                request.email(), request.password(), request.deviceName(), request.platform()));
    }

    @PostMapping("/auth/refresh")
    public SessionResponse refresh(@RequestBody RefreshRequest request) {
        return SessionResponse.of(auth.refresh(request.refreshToken()));
    }

    /** Redeems a pairing code minted by an already-trusted device. */
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

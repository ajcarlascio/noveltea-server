package com.noveltea.account;

import com.noveltea.account.AccountService.DeletionStatus;
import com.noveltea.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AccountController {

    private final AccountService account;

    public AccountController(AccountService account) {
        this.account = account;
    }

    public record ResetRequest(String email) {}

    public record ResetConfirmRequest(String token, String newPassword) {}

    public record DeleteAccountRequest(String password) {}

    /**
     * Always 202, whether or not the address is registered.
     *
     * <p>A different answer for a known address would make this an account-enumeration
     * oracle, and it needs no authentication to reach.
     */
    @PostMapping("/auth/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> requestReset(
            @RequestBody ResetRequest request, HttpServletRequest http) {
        account.requestReset(request.email(), http.getRemoteAddr());
        return Map.of("status", "if that address has an account, a reset link is on its way");
    }

    @PostMapping("/auth/password-reset/confirm")
    public Map<String, Object> confirmReset(@RequestBody ResetConfirmRequest request) {
        int signedOut = account.confirmReset(request.token(), request.newPassword());
        return Map.of("status", "password changed", "devicesSignedOut", signedOut);
    }

    /** Requires the password again: a borrowed unlocked laptop must not destroy novels. */
    @PostMapping("/account/deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeletionStatus requestDeletion(
            @AuthenticationPrincipal CurrentUser user, @RequestBody DeleteAccountRequest request) {
        return account.requestDeletion(user.userId(), request.password());
    }

    @GetMapping("/account/deletion")
    public DeletionStatus deletionStatus(@AuthenticationPrincipal CurrentUser user) {
        return account.status(user.userId());
    }

    @DeleteMapping("/account/deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelDeletion(@AuthenticationPrincipal CurrentUser user) {
        account.cancelDeletion(user.userId());
    }
}

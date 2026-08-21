package com.noveltea.account;

import com.noveltea.account.AccountService.DeletionStatus;
import com.noveltea.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Account", description = "Password reset and account deletion.")
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
    @Operation(
            summary = "Request a password reset email",
            description = "Public. Always answers 202, whether or not the address is "
                    + "registered — a different answer would make this an "
                    + "account-enumeration oracle. If mail is not configured "
                    + "(spring.mail.host unset), the reset link is written to the log "
                    + "instead of sent.",
            security = {})
    @PostMapping("/auth/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> requestReset(
            @RequestBody ResetRequest request, HttpServletRequest http) {
        account.requestReset(request.email(), http.getRemoteAddr());
        return Map.of("status", "if that address has an account, a reset link is on its way");
    }

    @Operation(
            summary = "Complete a password reset",
            description = "Public. Signs out every device holding a refresh token, "
                    + "reported as devicesSignedOut.",
            security = {})
    @PostMapping("/auth/password-reset/confirm")
    public Map<String, Object> confirmReset(@RequestBody ResetConfirmRequest request) {
        int signedOut = account.confirmReset(request.token(), request.newPassword());
        return Map.of("status", "password changed", "devicesSignedOut", signedOut);
    }

    /** Requires the password again: a borrowed unlocked laptop must not destroy novels. */
    @Operation(
            summary = "Schedule account deletion",
            description = "Requires the current password even though the caller is already "
                    + "authenticated, so a borrowed unlocked laptop cannot destroy an account. "
                    + "Deletion happens after a grace period (noveltea.account.deletion-grace), "
                    + "cancellable via DELETE on this same path.")
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

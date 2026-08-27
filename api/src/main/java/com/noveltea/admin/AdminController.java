package com.noveltea.admin;

import com.noveltea.admin.AdminService.NewAccount;
import com.noveltea.admin.AdminService.UserSummary;
import com.noveltea.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administration",
        description = "Instance administration: the accounts on this server. Nothing here "
                + "grants access to anyone's projects or documents.")
public class AdminController {

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    public record CreateUserRequest(
            String email, String password, String displayName, Boolean admin) {}

    public record SetPasswordRequest(String password) {}

    @Operation(
            summary = "List the accounts on this instance",
            description = "Administrators only; anyone else is answered 404, because a 403 "
                    + "would confirm the route exists.")
    @GetMapping("/users")
    public List<UserSummary> users(@AuthenticationPrincipal CurrentUser user) {
        return admin.listUsers(user.userId());
    }

    @Operation(
            summary = "Create an account",
            description = "Omit `password` to have one generated. Either way the response "
                    + "carries it once and it is never retrievable again — only its hash is "
                    + "stored — and the new account must choose its own before it can use "
                    + "any other route.")
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public NewAccount create(
            @AuthenticationPrincipal CurrentUser user, @RequestBody CreateUserRequest request) {
        return admin.createUser(
                user.userId(),
                request.email(),
                request.password(),
                request.displayName(),
                Boolean.TRUE.equals(request.admin()));
    }

    @Operation(
            summary = "Set an account's password",
            description = "For an account that has lost access on an instance with no mail "
                    + "configured, where the emailed reset would write its link to a log the "
                    + "account holder cannot read. Signs out every device that account had, "
                    + "and requires it to choose a new password immediately.")
    @PostMapping("/users/{userId}/password")
    public NewAccount setPassword(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID userId,
            @RequestBody SetPasswordRequest request) {
        return admin.resetPassword(user.userId(), userId, request.password());
    }
}

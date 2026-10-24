package Cafeteria.auth;

import Cafeteria.tenant.SlugRules;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AccountDtos {

    private AccountDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank @Size(max = 120) String businessName,
            @NotBlank @Size(min = 3, max = 63) @Pattern(regexp = SlugRules.PATTERN) String slug) {}

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {}

    public record TokenRequest(@NotBlank @Size(max = 128) String token) {}

    public record ResetPasswordRequest(
            @NotBlank @Size(max = 128) String token,
            @NotBlank @Size(min = 10, max = 72) String newPassword) {}

    public record MessageResponse(String message) {}
}

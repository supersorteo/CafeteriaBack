package Cafeteria.superadmin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class SuperAdminDtos {

    private SuperAdminDtos() {}

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 200) String password) {}

    public record CodeRequest(@NotBlank @Size(max = 12) String code) {}

    public record LoginResponse(String step, String maskedEmail, int expiresInSeconds) {}

    public record MeResponse(String email) {}

    public record Stats(long total, long active, long suspended, long published, long newLast7Days) {}

    public record TenantRow(UUID id, String slug, String name, boolean published, boolean suspended, Instant suspendedAt,
            String ownerEmail, boolean ownerVerified, long editors, Instant createdAt) {}

    public record TenantPage(List<TenantRow> content, int page, int size, long totalElements, int totalPages) {}

    public record DeleteRequest(@NotBlank @Size(max = 63) String confirmSlug) {}

    public record AuditEntry(UUID id, Instant at, String action, String targetLabel, String actor, String ip, String detail) {}
}

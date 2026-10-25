package Cafeteria.tenant;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantUserRepository extends JpaRepository<TenantUser, UUID> {

    Optional<TenantUser> findByUserId(UUID userId);

    boolean existsByTenantId(UUID tenantId);
}

package Cafeteria.media;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    Optional<MediaAsset> findByIdAndTenantId(UUID id, UUID tenantId);

    List<MediaAsset> findByIdInAndTenantId(Collection<UUID> ids, UUID tenantId);

    long countByTenantId(UUID tenantId);

    List<MediaAsset> findByTenantId(UUID tenantId);
}

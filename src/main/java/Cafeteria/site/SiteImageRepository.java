package Cafeteria.site;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SiteImageRepository extends JpaRepository<SiteImage, UUID> {

    List<SiteImage> findByTenantIdOrderBySlotAscPositionAsc(UUID tenantId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SiteImage s where s.tenantId = :tenantId")
    void deleteAllByTenantId(@Param("tenantId") UUID tenantId);
}

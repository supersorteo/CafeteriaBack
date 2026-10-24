package Cafeteria.site;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SiteStatRepository extends JpaRepository<SiteStat, UUID> {

    List<SiteStat> findByTenantIdOrderByPositionAsc(UUID tenantId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SiteStat s where s.tenantId = :tenantId")
    void deleteAllByTenantId(@Param("tenantId") UUID tenantId);
}

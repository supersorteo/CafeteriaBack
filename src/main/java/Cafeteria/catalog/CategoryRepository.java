package Cafeteria.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Category> findByTenantIdOrderBySortOrderAscIdAsc(UUID tenantId);

    long countByTenantId(UUID tenantId);

    @Query("select c.id from Category c where c.tenantId = :tenantId")
    List<UUID> findIdsByTenantId(@Param("tenantId") UUID tenantId);

    @Query("select coalesce(max(c.sortOrder), -1) from Category c where c.tenantId = :tenantId")
    int maxSortOrder(@Param("tenantId") UUID tenantId);

    @Modifying
    @Query("update Category c set c.sortOrder = :sortOrder where c.id = :id and c.tenantId = :tenantId")
    int updateSortOrder(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("sortOrder") int sortOrder);
}

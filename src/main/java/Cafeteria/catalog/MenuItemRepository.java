package Cafeteria.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuItemRepository extends JpaRepository<MenuItem, UUID>, JpaSpecificationExecutor<MenuItem> {

    Optional<MenuItem> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndActiveTrue(UUID tenantId);

    List<MenuItem> findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(UUID tenantId);

    List<MenuItem> findByTenantIdAndCategoryIdOrderBySortOrderAscIdAsc(UUID tenantId, UUID categoryId);

    long countByTenantIdAndCategoryId(UUID tenantId, UUID categoryId);

    @Query("select m.id from MenuItem m where m.tenantId = :tenantId and m.categoryId = :categoryId")
    List<UUID> findIdsByTenantIdAndCategoryId(@Param("tenantId") UUID tenantId, @Param("categoryId") UUID categoryId);

    @Query("select coalesce(max(m.sortOrder), -1) from MenuItem m where m.tenantId = :tenantId "
            + "and m.categoryId = :categoryId")
    int maxSortOrder(@Param("tenantId") UUID tenantId, @Param("categoryId") UUID categoryId);

    @Modifying
    @Query("update MenuItem m set m.sortOrder = :sortOrder where m.id = :id and m.tenantId = :tenantId")
    int updateSortOrder(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("sortOrder") int sortOrder);
}

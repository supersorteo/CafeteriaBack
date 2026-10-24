package Cafeteria.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.catalog.CatalogDtos.CategoryRequest;
import Cafeteria.catalog.CatalogDtos.CategoryResponse;
import Cafeteria.shared.ApiException;
import Cafeteria.tenant.TenantRepository;

@Service
@Transactional
public class CategoryService {

    static final int MAX_CATEGORIES_PER_TENANT = 30;

    private final CategoryRepository categories;
    private final MenuItemRepository items;
    private final TenantRepository tenants;

    public CategoryService(CategoryRepository categories, MenuItemRepository items, TenantRepository tenants) {
        this.categories = categories;
        this.items = items;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(UUID tenantId) {
        return categories.findByTenantIdOrderBySortOrderAscIdAsc(tenantId).stream().map(CategoryResponse::of).toList();
    }

    public CategoryResponse create(UUID tenantId, CategoryRequest request) {
        lockTenant(tenantId);
        if (categories.countByTenantId(tenantId) >= MAX_CATEGORIES_PER_TENANT) {
            throw ApiException.conflict("limit_reached", "Se alcanzó el máximo de categorías del negocio");
        }
        Category category = new Category(tenantId, request.name().strip(), categories.maxSortOrder(tenantId) + 1);
        return CategoryResponse.of(categories.saveAndFlush(category));
    }

    public CategoryResponse update(UUID tenantId, UUID id, CategoryRequest request) {
        Category category = find(tenantId, id);
        CatalogSupport.requireVersion(request.version(), category.getVersion());
        category.setName(request.name().strip());
        return CategoryResponse.of(categories.saveAndFlush(category));
    }

    public void delete(UUID tenantId, UUID id) {
        lockTenant(tenantId);
        Category category = find(tenantId, id);
        if (items.countByTenantIdAndCategoryId(tenantId, id) > 0) {
            throw ApiException.conflict("category_not_empty", "La categoría tiene productos; muévelos o elimínalos antes");
        }
        categories.delete(category);
        categories.flush();
    }

    public List<CategoryResponse> reorder(UUID tenantId, List<UUID> ids) {
        lockTenant(tenantId);
        CatalogSupport.requireCompleteOrder(ids, categories.findIdsByTenantId(tenantId));
        for (int position = 0; position < ids.size(); position++) {
            categories.updateSortOrder(ids.get(position), tenantId, position);
        }
        categories.flush();
        return list(tenantId);
    }

    Category find(UUID tenantId, UUID id) {
        return categories.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> ApiException.notFound("Categoría no encontrada"));
    }

    private void lockTenant(UUID tenantId) {
        tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
    }
}

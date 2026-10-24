package Cafeteria.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.catalog.CatalogDtos.MenuItemRequest;
import Cafeteria.catalog.CatalogDtos.MenuItemResponse;
import Cafeteria.media.MediaAsset;
import Cafeteria.media.MediaService;
import Cafeteria.shared.ApiException;
import Cafeteria.shared.PageResponse;
import Cafeteria.tenant.TenantRepository;
import jakarta.persistence.criteria.Predicate;

@Service
@Transactional
public class MenuItemService {

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_ITEMS_PER_TENANT = 500;

    private final MenuItemRepository items;
    private final CategoryRepository categories;
    private final TenantRepository tenants;
    private final MediaService media;

    public MenuItemService(MenuItemRepository items, CategoryRepository categories, TenantRepository tenants,
            MediaService media) {
        this.items = items;
        this.categories = categories;
        this.tenants = tenants;
        this.media = media;
    }

    @Transactional(readOnly = true)
    public PageResponse<MenuItemResponse> list(UUID tenantId, UUID categoryId, Boolean active, int page, int size) {
        Specification<MenuItem> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by("sortOrder", "id"));
        Page<MenuItem> result = items.findAll(spec, pageable);
        Map<UUID, String> urls = imageUrls(tenantId, result.getContent());
        return PageResponse.of(result, item -> MenuItemResponse.of(item, urls.get(item.getImageId())));
    }

    public MenuItemResponse create(UUID tenantId, MenuItemRequest request) {
        lockTenant(tenantId);
        if (items.countByTenantId(tenantId) >= MAX_ITEMS_PER_TENANT) {
            throw ApiException.conflict("limit_reached", "Se alcanzó el máximo de productos del negocio");
        }
        requireOwnCategory(tenantId, request.categoryId());
        MenuItem item = new MenuItem(tenantId, request.categoryId(), request.name().strip(),
                blankToNull(request.description()), request.price(), blankToNull(request.tag()),
                items.maxSortOrder(tenantId, request.categoryId()) + 1);
        item.setActive(request.active() == null || request.active());
        if (request.imageId() != null) {
            item.setImageId(media.requireReady(tenantId, request.imageId(), "imageId").getId());
        }
        return respond(tenantId, items.saveAndFlush(item));
    }

    public MenuItemResponse update(UUID tenantId, UUID id, MenuItemRequest request) {
        lockTenant(tenantId);
        MenuItem item = find(tenantId, id);
        CatalogSupport.requireVersion(request.version(), item.getVersion());
        boolean remove = Boolean.TRUE.equals(request.removeImage());
        if (remove && request.imageId() != null) {
            throw ApiException.invalidField("removeImage", "No se puede enviar imageId y quitar la imagen a la vez");
        }
        UUID previousImage = item.getImageId();
        if (!item.getCategoryId().equals(request.categoryId())) {
            requireOwnCategory(tenantId, request.categoryId());
            item.setCategoryId(request.categoryId());
            item.setSortOrder(items.maxSortOrder(tenantId, request.categoryId()) + 1);
        }
        item.setName(request.name().strip());
        item.setDescription(blankToNull(request.description()));
        item.setPrice(request.price());
        item.setTag(blankToNull(request.tag()));
        if (request.active() != null) {
            item.setActive(request.active());
        }
        if (request.imageId() != null) {
            item.setImageId(media.requireReady(tenantId, request.imageId(), "imageId").getId());
        } else if (remove) {
            item.setImageId(null);
        }
        MenuItem saved = items.saveAndFlush(item);
        if (previousImage != null && !Objects.equals(previousImage, saved.getImageId())) {
            media.deleteIfUnreferenced(tenantId, previousImage);
        }
        return respond(tenantId, saved);
    }

    public void delete(UUID tenantId, UUID id) {
        lockTenant(tenantId);
        MenuItem item = find(tenantId, id);
        UUID imageId = item.getImageId();
        items.delete(item);
        items.flush();
        media.deleteIfUnreferenced(tenantId, imageId);
    }

    public List<MenuItemResponse> reorder(UUID tenantId, UUID categoryId, List<UUID> ids) {
        lockTenant(tenantId);
        requireOwnCategory(tenantId, categoryId);
        CatalogSupport.requireCompleteOrder(ids, items.findIdsByTenantIdAndCategoryId(tenantId, categoryId));
        for (int position = 0; position < ids.size(); position++) {
            items.updateSortOrder(ids.get(position), tenantId, position);
        }
        items.flush();
        List<MenuItem> ordered = items.findAll((root, query, cb) -> cb.and(cb.equal(root.get("tenantId"), tenantId),
                cb.equal(root.get("categoryId"), categoryId)), Sort.by("sortOrder", "id"));
        Map<UUID, String> urls = imageUrls(tenantId, ordered);
        return ordered.stream().map(item -> MenuItemResponse.of(item, urls.get(item.getImageId()))).toList();
    }

    private MenuItemResponse respond(UUID tenantId, MenuItem item) {
        return MenuItemResponse.of(item, imageUrls(tenantId, List.of(item)).get(item.getImageId()));
    }

    private Map<UUID, String> imageUrls(UUID tenantId, Collection<MenuItem> source) {
        List<UUID> imageIds = source.stream().map(MenuItem::getImageId).filter(Objects::nonNull).distinct().toList();
        return media.byIds(tenantId, imageIds).values().stream()
                .collect(Collectors.toMap(MediaAsset::getId, MediaAsset::getPublicUrl));
    }

    private MenuItem find(UUID tenantId, UUID id) {
        return items.findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
    }

    private void requireOwnCategory(UUID tenantId, UUID categoryId) {
        if (categories.findByIdAndTenantId(categoryId, tenantId).isEmpty()) {
            throw ApiException.badRequest("invalid_category", "La categoría no existe en este negocio");
        }
    }

    private void lockTenant(UUID tenantId) {
        tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

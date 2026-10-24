package Cafeteria.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.catalog.CatalogDtos.CategoryRequest;
import Cafeteria.catalog.CatalogDtos.CategoryResponse;
import Cafeteria.catalog.CatalogDtos.MenuItemRequest;
import Cafeteria.catalog.CatalogDtos.MenuItemResponse;
import Cafeteria.catalog.CatalogDtos.OrderRequest;
import Cafeteria.security.TenantContext;
import Cafeteria.shared.PageResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminCatalogController {

    private final TenantContext tenantContext;
    private final CategoryService categories;
    private final MenuItemService items;

    public AdminCatalogController(TenantContext tenantContext, CategoryService categories, MenuItemService items) {
        this.tenantContext = tenantContext;
        this.categories = categories;
        this.items = items;
    }

    private UUID tenantId() {
        return tenantContext.require().tenantId();
    }

    @GetMapping("/categories")
    public List<CategoryResponse> listCategories() {
        return categories.list(tenantId());
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@Valid @RequestBody CategoryRequest request) {
        return categories.create(tenantId(), request);
    }

    @PutMapping("/categories/{id}")
    public CategoryResponse updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return categories.update(tenantId(), id, request);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable UUID id) {
        categories.delete(tenantId(), id);
    }

    @PutMapping("/categories/order")
    public List<CategoryResponse> reorderCategories(@Valid @RequestBody OrderRequest request) {
        return categories.reorder(tenantId(), request.ids());
    }

    @PutMapping("/categories/{id}/item-order")
    public List<MenuItemResponse> reorderItems(@PathVariable UUID id, @Valid @RequestBody OrderRequest request) {
        return items.reorder(tenantId(), id, request.ids());
    }

    @GetMapping("/menu-items")
    public PageResponse<MenuItemResponse> listItems(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return items.list(tenantId(), categoryId, active, page, size);
    }

    @PostMapping("/menu-items")
    @ResponseStatus(HttpStatus.CREATED)
    public MenuItemResponse createItem(@Valid @RequestBody MenuItemRequest request) {
        return items.create(tenantId(), request);
    }

    @PutMapping("/menu-items/{id}")
    public MenuItemResponse updateItem(@PathVariable UUID id, @Valid @RequestBody MenuItemRequest request) {
        return items.update(tenantId(), id, request);
    }

    @DeleteMapping("/menu-items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable UUID id) {
        items.delete(tenantId(), id);
    }
}

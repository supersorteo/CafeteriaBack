package Cafeteria.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {

    private CatalogDtos() {}

    public record CategoryRequest(
            @NotBlank @Size(max = 120) String name,
            Long version) {}

    public record CategoryResponse(UUID id, String name, int sortOrder, long version) {
        static CategoryResponse of(Category c) {
            return new CategoryResponse(c.getId(), c.getName(), c.getSortOrder(), c.getVersion());
        }
    }

    public record MenuItemRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
            @Size(max = 40) String tag,
            Boolean active,
            Long version,
            UUID imageId,
            Boolean removeImage) {}

    public record MenuItemResponse(UUID id, UUID categoryId, String name, String description, BigDecimal price,
            String tag, boolean active, int sortOrder, long version, UUID imageId, String imageUrl) {
        static MenuItemResponse of(MenuItem m, String imageUrl) {
            return new MenuItemResponse(m.getId(), m.getCategoryId(), m.getName(), m.getDescription(), m.getPrice(),
                    m.getTag(), m.isActive(), m.getSortOrder(), m.getVersion(), m.getImageId(), imageUrl);
        }
    }

    public record OrderRequest(@NotNull List<UUID> ids) {}
}

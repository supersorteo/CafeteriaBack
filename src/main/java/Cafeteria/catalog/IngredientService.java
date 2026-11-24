package Cafeteria.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.shared.ApiException;
import Cafeteria.tenant.TenantRepository;

@Service
@Transactional
public class IngredientService {

    public static final int MAX_PER_TENANT = 100;
    public static final int MAX_PER_ITEM = 30;

    public record IngredientRef(UUID id, String name) {}

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;

    public IngredientService(JdbcTemplate jdbc, TenantRepository tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public List<IngredientRef> list(UUID tenantId) {
        return jdbc.query("SELECT id, name FROM ingredients WHERE tenant_id = ? ORDER BY lower(name), id",
                (rs, i) -> new IngredientRef(rs.getObject(1, UUID.class), rs.getString(2)), tenantId);
    }

    public IngredientRef create(UUID tenantId, String name) {
        tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
        Long count = jdbc.queryForObject("SELECT count(*) FROM ingredients WHERE tenant_id = ?", Long.class, tenantId);
        if (count != null && count >= MAX_PER_TENANT) {
            throw ApiException.conflict("limit_reached", "Se alcanzó el máximo de " + MAX_PER_TENANT + " ingredientes");
        }
        String clean = clean(name);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO ingredients (id, tenant_id, name) VALUES (?, ?, ?)", id, tenantId, clean);
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
        return new IngredientRef(id, clean);
    }

    public IngredientRef update(UUID tenantId, UUID id, String name) {
        tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
        String clean = clean(name);
        int updated;
        try {
            updated = jdbc.update("UPDATE ingredients SET name = ? WHERE id = ? AND tenant_id = ?", clean, id, tenantId);
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
        if (updated == 0) {
            throw ApiException.notFound("Ingrediente no encontrado");
        }
        return new IngredientRef(id, clean);
    }

    public void delete(UUID tenantId, UUID id) {
        tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
        if (jdbc.update("DELETE FROM ingredients WHERE id = ? AND tenant_id = ?", id, tenantId) == 0) {
            throw ApiException.notFound("Ingrediente no encontrado");
        }
    }

    public void setForItem(UUID tenantId, UUID itemId, List<UUID> ingredientIds) {
        List<UUID> ids = ingredientIds.stream().distinct().toList();
        if (ids.size() > MAX_PER_ITEM) {
            throw ApiException.invalidField("ingredientIds", "Un producto admite hasta " + MAX_PER_ITEM + " ingredientes");
        }
        Set<UUID> own = new HashSet<>(jdbc.query("SELECT id FROM ingredients WHERE tenant_id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId));
        if (!own.containsAll(ids)) {
            throw ApiException.invalidField("ingredientIds", "Algún ingrediente no existe en este negocio");
        }
        jdbc.update("DELETE FROM menu_item_ingredients WHERE tenant_id = ? AND menu_item_id = ?", tenantId, itemId);
        for (int position = 0; position < ids.size(); position++) {
            jdbc.update("INSERT INTO menu_item_ingredients (tenant_id, menu_item_id, ingredient_id, position) VALUES (?, ?, ?, ?)",
                    tenantId, itemId, ids.get(position), position);
        }
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<IngredientRef>> forItems(UUID tenantId, Collection<UUID> itemIds) {
        Map<UUID, List<IngredientRef>> byItem = new HashMap<>();
        if (itemIds.isEmpty()) {
            return byItem;
        }
        Set<UUID> wanted = new HashSet<>(itemIds);
        jdbc.query("SELECT mi.menu_item_id, i.id, i.name FROM menu_item_ingredients mi "
                + "JOIN ingredients i ON i.tenant_id = mi.tenant_id AND i.id = mi.ingredient_id "
                + "WHERE mi.tenant_id = ? ORDER BY mi.menu_item_id, mi.position", rs -> {
                    UUID item = rs.getObject(1, UUID.class);
                    if (wanted.contains(item)) {
                        byItem.computeIfAbsent(item, k -> new ArrayList<>())
                                .add(new IngredientRef(rs.getObject(2, UUID.class), rs.getString(3)));
                    }
                }, tenantId);
        return byItem;
    }

    private static String clean(String name) {
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty()) {
            throw ApiException.invalidField("name", "Escribe el nombre del ingrediente");
        }
        return clean;
    }

    private static ApiException duplicate() {
        return ApiException.conflict("duplicate_ingredient", "Ya tienes un ingrediente con ese nombre");
    }
}

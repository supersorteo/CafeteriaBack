package Cafeteria.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import Cafeteria.auth.User;
import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantUser;

class TenantIntegrityTest extends IntegrationTestSupport {

    private void insertItem(UUID tenantId, UUID categoryId, String price) {
        jdbc.update("INSERT INTO menu_items (id, tenant_id, category_id, name, price) VALUES (?,?,?,?,?::numeric)",
                UUID.randomUUID(), tenantId, categoryId, "Plato", price);
    }

    private Category category(Tenant tenant, String name) {
        return categories.save(new Category(tenant.getId(), name, 0));
    }

    @org.springframework.beans.factory.annotation.Autowired
    CategoryRepository categories;

    @org.springframework.beans.factory.annotation.Autowired
    MenuItemRepository items;

    @Test
    void productoNoPuedeReferenciarCategoriaDeOtroNegocio() {
        Tenant a = tenant("negocio-a");
        Tenant b = tenant("negocio-b");
        Category categoriaB = category(b, "Bebidas B");

        assertThatThrownBy(() -> insertItem(a.getId(), categoriaB.getId(), "10"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(items.countByTenantId(a.getId())).isZero();
    }

    @Test
    void precioNegativoSeRechaza() {
        Tenant a = tenant("negocio-a");
        Category c = category(a, "Platos");

        assertThatThrownBy(() -> insertItem(a.getId(), c.getId(), "-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailDuplicadoSinDistinguirMayusculasSeRechaza() {
        Tenant a = tenant("negocio-a");
        member("dup@a.test", a, Role.OWNER, true);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (id, email, password_hash) VALUES (?, 'DUP@a.test', 'x')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> users.saveAndFlush(new User("dup@a.test", "x")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void slugDuplicadoReservadoOMalFormadoSeRechaza() {
        tenant("negocio-a");

        for (String slug : new String[] { "negocio-a", "admin", "ab", "-malo", "doble--guion", "Mayus" }) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?,?,?)",
                    UUID.randomUUID(), slug, "X"))
                    .as(slug)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void soloPuedeHaberUnOwnerYUnNegocioPorUsuario() {
        Tenant a = tenant("negocio-a");
        member("owner@a.test", a, Role.OWNER, true);
        User otro = users.save(new User("otro@a.test", "x"));
        User editor = member("editor@a.test", a, Role.EDITOR, true);
        Tenant b = tenant("negocio-b");

        assertThatThrownBy(() -> memberships.saveAndFlush(new TenantUser(otro.getId(), a.getId(), Role.OWNER)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> memberships.saveAndFlush(new TenantUser(editor.getId(), b.getId(), Role.EDITOR)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void borrarCategoriaConProductosEstaRestringido() {
        Tenant a = tenant("negocio-a");
        Category c = category(a, "Platos");
        items.save(new MenuItem(a.getId(), c.getId(), "Plato", null, new BigDecimal("10.00"), null, 0));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM categories WHERE id = ?", c.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(items.countByTenantId(a.getId())).isEqualTo(1);
    }
}

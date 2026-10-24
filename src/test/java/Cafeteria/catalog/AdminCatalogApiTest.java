package Cafeteria.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class AdminCatalogApiTest extends IntegrationTestSupport {

    @Autowired
    CategoryRepository categories;
    @Autowired
    MenuItemRepository items;

    Tenant a;
    Tenant b;
    Cookie ownerA;
    Cookie editorA;
    Category catA;
    Category catB;
    MenuItem itemB;

    @BeforeEach
    void fixtures() throws Exception {
        a = tenant("negocio-a");
        b = tenant("negocio-b");
        member("owner@a.test", a, Role.OWNER, true);
        member("editor@a.test", a, Role.EDITOR, true);
        member("owner@b.test", b, Role.OWNER, true);
        catA = categories.save(new Category(a.getId(), "Bebidas A", 0));
        catB = categories.save(new Category(b.getId(), "Bebidas B", 0));
        itemB = items.save(new MenuItem(b.getId(), catB.getId(), "Plato B", "desc", new BigDecimal("10.00"), "tag", 0));
        ownerA = login("owner@a.test");
        editorA = login("editor@a.test");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Cookie session, String json)
            throws Exception {
        return withXsrf(request.cookie(session), newXsrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String categoryJson(String name, Long version) {
        return "{\"name\":\"%s\",\"version\":%s}".formatted(name, version);
    }

    private String itemJson(UUID categoryId, String name, String price, Long version) {
        return "{\"categoryId\":\"%s\",\"name\":\"%s\",\"description\":\"d\",\"price\":%s,\"tag\":\"t\",\"active\":true,\"version\":%s}"
                .formatted(categoryId, name, price, version);
    }

    private String idsJson(UUID... ids) {
        return "{\"ids\":[" + String.join(",", java.util.Arrays.stream(ids).map(i -> "\"" + i + "\"").toList()) + "]}";
    }

    private void assertBIntact() {
        Category storedCategory = categories.findById(catB.getId()).orElseThrow();
        MenuItem storedItem = items.findById(itemB.getId()).orElseThrow();
        assertThat(storedCategory.getName()).isEqualTo("Bebidas B");
        assertThat(storedCategory.getVersion()).isEqualTo(catB.getVersion());
        assertThat(storedItem.getName()).isEqualTo("Plato B");
        assertThat(storedItem.getPrice()).isEqualByComparingTo("10.00");
        assertThat(storedItem.getVersion()).isEqualTo(itemB.getVersion());
        assertThat(categories.countByTenantId(b.getId())).isEqualTo(1);
        assertThat(items.countByTenantId(b.getId())).isEqualTo(1);
    }

    @Test
    void ownerYEditorPuedenCrearYListarCategorias() throws Exception {
        mvc.perform(write(post("/api/v1/admin/categories"), ownerA, categoryJson("Postres", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Postres"))
                .andExpect(jsonPath("$.sortOrder").value(1));
        mvc.perform(write(post("/api/v1/admin/categories"), editorA, categoryJson("  Entrantes  ", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Entrantes"))
                .andExpect(jsonPath("$.sortOrder").value(2));

        mvc.perform(get("/api/v1/admin/categories").cookie(editorA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].name", contains("Bebidas A", "Postres", "Entrantes")));
    }

    @Test
    void listadosSoloMuestranDatosDelPropioNegocio() throws Exception {
        items.save(new MenuItem(a.getId(), catA.getId(), "Plato A", null, new BigDecimal("5.00"), null, 0));

        mvc.perform(get("/api/v1/admin/categories").cookie(ownerA))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(catA.getId().toString()));
        mvc.perform(get("/api/v1/admin/menu-items").cookie(ownerA))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Plato A"));
    }

    @Test
    void sinSesionDevuelve401() throws Exception {
        mvc.perform(get("/api/v1/admin/categories")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/menu-items")).andExpect(status().isUnauthorized());
    }

    @Test
    void escrituraSinCsrfDevuelve403SinMutar() throws Exception {
        mvc.perform(post("/api/v1/admin/categories").cookie(ownerA).contentType(MediaType.APPLICATION_JSON)
                        .content(categoryJson("Intruso", null)))
                .andExpect(status().isForbidden());

        assertThat(categories.countByTenantId(a.getId())).isEqualTo(1);
    }

    @Test
    void aNoPuedeLeerEditarNiBorrarCategoriaDeB() throws Exception {
        mvc.perform(write(put("/api/v1/admin/categories/" + catB.getId()), ownerA,
                        categoryJson("Hackeada", catB.getVersion())))
                .andExpect(status().isNotFound());
        mvc.perform(write(delete("/api/v1/admin/categories/" + catB.getId()), ownerA, "{}"))
                .andExpect(status().isNotFound());

        assertBIntact();
    }

    @Test
    void aNoPuedeEditarNiBorrarProductoDeB() throws Exception {
        mvc.perform(write(put("/api/v1/admin/menu-items/" + itemB.getId()), ownerA,
                        itemJson(catA.getId(), "Hackeado", "1", itemB.getVersion())))
                .andExpect(status().isNotFound());
        mvc.perform(write(delete("/api/v1/admin/menu-items/" + itemB.getId()), ownerA, "{}"))
                .andExpect(status().isNotFound());

        assertBIntact();
    }

    @Test
    void productoDeANoPuedeReferenciarCategoriaDeB() throws Exception {
        mvc.perform(write(post("/api/v1/admin/menu-items"), ownerA, itemJson(catB.getId(), "Cruzado", "9.5", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_category"));

        MenuItem own = items.save(new MenuItem(a.getId(), catA.getId(), "Propio", null, new BigDecimal("1.00"), null, 0));
        mvc.perform(write(put("/api/v1/admin/menu-items/" + own.getId()), ownerA,
                        itemJson(catB.getId(), "Propio", "1", own.getVersion())))
                .andExpect(status().isBadRequest());

        assertThat(items.countByTenantId(a.getId())).isEqualTo(1);
        assertThat(items.findById(own.getId()).orElseThrow().getCategoryId()).isEqualTo(catA.getId());
        assertBIntact();
    }

    @Test
    void ordenConCategoriaAjenaSeRechazaSinCambios() throws Exception {
        Category second = categories.save(new Category(a.getId(), "Segunda", 1));

        mvc.perform(write(put("/api/v1/admin/categories/order"), ownerA, idsJson(second.getId(), catB.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_order"));

        assertThat(categories.findById(catA.getId()).orElseThrow().getSortOrder()).isZero();
        assertThat(categories.findById(second.getId()).orElseThrow().getSortOrder()).isEqualTo(1);
        assertBIntact();
    }

    @Test
    void ordenIncompletoORepetidoSeRechaza() throws Exception {
        Category second = categories.save(new Category(a.getId(), "Segunda", 1));

        mvc.perform(write(put("/api/v1/admin/categories/order"), ownerA, idsJson(second.getId())))
                .andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/v1/admin/categories/order"), ownerA,
                        idsJson(second.getId(), second.getId())))
                .andExpect(status().isBadRequest());

        assertThat(categories.findById(catA.getId()).orElseThrow().getSortOrder()).isZero();
    }

    @Test
    void reordenarCategoriasValidoPersisteSinCambiarVersiones() throws Exception {
        Category second = categories.save(new Category(a.getId(), "Segunda", 1));
        long versionAntes = categories.findById(catA.getId()).orElseThrow().getVersion();

        mvc.perform(write(put("/api/v1/admin/categories/order"), ownerA, idsJson(second.getId(), catA.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(second.getId().toString()))
                .andExpect(jsonPath("$[1].id").value(catA.getId().toString()));

        Category stored = categories.findById(catA.getId()).orElseThrow();
        assertThat(stored.getSortOrder()).isEqualTo(1);
        assertThat(stored.getVersion()).isEqualTo(versionAntes);
    }

    @Test
    void reordenarProductosDeUnaCategoria() throws Exception {
        MenuItem x = items.save(new MenuItem(a.getId(), catA.getId(), "X", null, BigDecimal.ONE, null, 0));
        MenuItem y = items.save(new MenuItem(a.getId(), catA.getId(), "Y", null, BigDecimal.ONE, null, 1));

        mvc.perform(write(put("/api/v1/admin/categories/" + catA.getId() + "/item-order"), ownerA,
                        idsJson(y.getId(), x.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Y", "X")));

        mvc.perform(write(put("/api/v1/admin/categories/" + catA.getId() + "/item-order"), ownerA,
                        idsJson(y.getId(), itemB.getId())))
                .andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/v1/admin/categories/" + catB.getId() + "/item-order"), ownerA,
                        idsJson(itemB.getId())))
                .andExpect(status().isBadRequest());
        assertBIntact();
    }

    @Test
    void borrarCategoriaConProductosDevuelve409YConservaTodo() throws Exception {
        items.save(new MenuItem(a.getId(), catA.getId(), "Plato A", null, BigDecimal.ONE, null, 0));

        mvc.perform(write(delete("/api/v1/admin/categories/" + catA.getId()), ownerA, "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("category_not_empty"));

        assertThat(categories.findById(catA.getId())).isPresent();
        assertThat(items.countByTenantId(a.getId())).isEqualTo(1);
    }

    @Test
    void borrarCategoriaVaciaFunciona() throws Exception {
        mvc.perform(write(delete("/api/v1/admin/categories/" + catA.getId()), editorA, "{}"))
                .andExpect(status().isNoContent());

        assertThat(categories.findById(catA.getId())).isEmpty();
    }

    @Test
    void versionAntiguaDevuelve409SinSobrescribir() throws Exception {
        long v0 = catA.getVersion();
        mvc.perform(write(put("/api/v1/admin/categories/" + catA.getId()), ownerA, categoryJson("Nueva", v0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(v0 + 1));

        mvc.perform(write(put("/api/v1/admin/categories/" + catA.getId()), editorA, categoryJson("Pisada", v0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("version_conflict"));

        assertThat(categories.findById(catA.getId()).orElseThrow().getName()).isEqualTo("Nueva");
    }

    @Test
    void actualizarSinVersionSeRechaza() throws Exception {
        mvc.perform(write(put("/api/v1/admin/categories/" + catA.getId()), ownerA, "{\"name\":\"Sin version\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("version_required"));
    }

    @Test
    void productoCrudCompletoYMoverDeCategoriaLoPoneAlFinal() throws Exception {
        Category second = categories.save(new Category(a.getId(), "Segunda", 1));
        items.save(new MenuItem(a.getId(), second.getId(), "Existente", null, BigDecimal.ONE, null, 0));

        String created = mvc.perform(write(post("/api/v1/admin/menu-items"), editorA,
                        itemJson(catA.getId(), "Nuevo", "12.50", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(12.5))
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.id"));
        long version = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.version")).longValue();

        mvc.perform(write(put("/api/v1/admin/menu-items/" + id), ownerA,
                        itemJson(second.getId(), "Movido", "13", version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(second.getId().toString()))
                .andExpect(jsonPath("$.sortOrder").value(1))
                .andExpect(jsonPath("$.name").value("Movido"));

        mvc.perform(write(delete("/api/v1/admin/menu-items/" + id), ownerA, "{}")).andExpect(status().isNoContent());
        assertThat(items.findById(id)).isEmpty();
    }

    @Test
    void validacionesDeProductoDevuelvenErroresPorCampo() throws Exception {
        mvc.perform(write(post("/api/v1/admin/menu-items"), ownerA, itemJson(catA.getId(), "   ", "-1", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItems("name", "price")));
        mvc.perform(write(post("/api/v1/admin/menu-items"), ownerA, itemJson(catA.getId(), "A", "1.234", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/v1/admin/menu-items"), ownerA, "{no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_request"));

        assertThat(items.countByTenantId(a.getId())).isZero();
    }

    @Test
    void paginacionLimitaElTamanoYFiltraPorCategoriaYEstado() throws Exception {
        for (int i = 0; i < 3; i++) {
            MenuItem item = new MenuItem(a.getId(), catA.getId(), "P" + i, null, BigDecimal.ONE, null, i);
            item.setActive(i != 1);
            items.save(item);
        }

        mvc.perform(get("/api/v1/admin/menu-items?size=1000").cookie(ownerA))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/v1/admin/menu-items?size=2&page=1").cookie(ownerA))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/admin/menu-items?active=false").cookie(ownerA))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("P1"));
        mvc.perform(get("/api/v1/admin/menu-items?categoryId=" + catB.getId()).cookie(ownerA))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void idMalformadoDevuelve400() throws Exception {
        mvc.perform(write(put("/api/v1/admin/categories/no-es-uuid"), ownerA, categoryJson("X", 0L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
    }
}

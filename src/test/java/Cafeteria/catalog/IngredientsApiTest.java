package Cafeteria.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

class IngredientsApiTest extends IntegrationTestSupport {

    @Autowired
    CategoryRepository categories;

    Tenant a;
    Tenant b;
    Cookie ownerA;
    Cookie editorA;
    Cookie ownerB;
    Category categoryA;

    @BeforeEach
    void fixtures() throws Exception {
        a = tenant("negocio-a");
        b = tenant("negocio-b");
        member("owner@a.test", a, Role.OWNER, true);
        member("editor@a.test", a, Role.EDITOR, true);
        member("owner@b.test", b, Role.OWNER, true);
        ownerA = login("owner@a.test");
        editorA = login("editor@a.test");
        ownerB = login("owner@b.test");
        categoryA = categories.save(new Category(a.getId(), "Platos", 0));
        jdbc.update("UPDATE tenants SET published = TRUE");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Cookie session, String json) throws Exception {
        return withXsrf(request.cookie(session), newXsrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String createIngredient(Cookie session, String name) throws Exception {
        String body = mvc.perform(write(post("/api/v1/admin/ingredients"), session, "{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }

    private String createItem(String name, String... ingredientIds) throws Exception {
        String ids = ingredientIds.length == 0 ? "" : ",\"ingredientIds\":[\"" + String.join("\",\"", ingredientIds) + "\"]";
        String body = mvc.perform(write(post("/api/v1/admin/menu-items"), ownerA,
                "{\"categoryId\":\"%s\",\"name\":\"%s\",\"price\":5.00%s}".formatted(categoryA.getId(), name, ids)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }

    @Test
    void secreaEditaYBorraUnIngredienteYElEditorTambienPuede() throws Exception {
        String id = createIngredient(ownerA, "  Queso   mozzarella ");
        createIngredient(editorA, "Albahaca");

        mvc.perform(get("/api/v1/admin/ingredients").cookie(ownerA))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Albahaca")).andExpect(jsonPath("$[1].name").value("Queso mozzarella"));
        mvc.perform(write(put("/api/v1/admin/ingredients/" + id), ownerA, "{\"name\":\"Mozzarella\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Mozzarella"));
        mvc.perform(write(delete("/api/v1/admin/ingredients/" + id), editorA, "")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/ingredients").cookie(ownerA)).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void noAdmiteNombresRepetidosSinImportarMayusculasNiVaciosNiPasarseDelTope() throws Exception {
        createIngredient(ownerA, "Tomate");

        mvc.perform(write(post("/api/v1/admin/ingredients"), ownerA, "{\"name\":\" TOMATE \"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate_ingredient"));
        mvc.perform(write(post("/api/v1/admin/ingredients"), ownerA, "{\"name\":\"   \"}")).andExpect(status().isBadRequest());

        for (int i = 0; i < 99; i++) {
            jdbc.update("INSERT INTO ingredients (id, tenant_id, name) VALUES (?, ?, ?)", UUID.randomUUID(), a.getId(), "Relleno " + i);
        }
        mvc.perform(write(post("/api/v1/admin/ingredients"), ownerA, "{\"name\":\"Uno de más\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("limit_reached"));
    }

    @Test
    void cadaNegocioSoloVeYTocaSusIngredientes() throws Exception {
        String ofA = createIngredient(ownerA, "Cebolla");
        createIngredient(ownerB, "Cebolla");

        mvc.perform(get("/api/v1/admin/ingredients").cookie(ownerB)).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(write(put("/api/v1/admin/ingredients/" + ofA), ownerB, "{\"name\":\"Robado\"}")).andExpect(status().isNotFound());
        mvc.perform(write(delete("/api/v1/admin/ingredients/" + ofA), ownerB, "")).andExpect(status().isNotFound());
        mvc.perform(write(post("/api/v1/admin/menu-items"), ownerA, "{\"categoryId\":\"%s\",\"name\":\"X\",\"price\":1,\"ingredientIds\":[\"%s\"]}"
                .formatted(categoryA.getId(), UUID.randomUUID()))).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT name FROM ingredients WHERE id = ?::uuid", String.class, ofA)).isEqualTo("Cebolla");
    }

    @Test
    void elProductoGuardaSusIngredientesYLosDevuelveEnOrden() throws Exception {
        String queso = createIngredient(ownerA, "Queso");
        String jamon = createIngredient(ownerA, "Jamón");
        String itemId = createItem("Pizza", jamon, queso);

        mvc.perform(get("/api/v1/admin/menu-items").cookie(ownerA))
                .andExpect(jsonPath("$.content[0].ingredients", hasSize(2)))
                .andExpect(jsonPath("$.content[0].ingredients[0].name").value("Jamón"))
                .andExpect(jsonPath("$.content[0].ingredients[1].name").value("Queso"));

        long version = tenantItemVersion(itemId);
        mvc.perform(write(put("/api/v1/admin/menu-items/" + itemId), ownerA,
                "{\"categoryId\":\"%s\",\"name\":\"Pizza\",\"price\":5.00,\"version\":%d,\"ingredientIds\":[\"%s\"]}"
                        .formatted(categoryA.getId(), version, queso)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ingredients", hasSize(1))).andExpect(jsonPath("$.ingredients[0].name").value("Queso"));

        long next = tenantItemVersion(itemId);
        mvc.perform(write(put("/api/v1/admin/menu-items/" + itemId), ownerA,
                "{\"categoryId\":\"%s\",\"name\":\"Pizza\",\"price\":5.00,\"version\":%d}".formatted(categoryA.getId(), next)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ingredients", hasSize(1)));
        long last = tenantItemVersion(itemId);
        mvc.perform(write(put("/api/v1/admin/menu-items/" + itemId), ownerA,
                "{\"categoryId\":\"%s\",\"name\":\"Pizza\",\"price\":5.00,\"version\":%d,\"ingredientIds\":[]}".formatted(categoryA.getId(), last)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ingredients", hasSize(0)));
    }

    private long tenantItemVersion(String itemId) {
        return jdbc.queryForObject("SELECT version FROM menu_items WHERE id = ?::uuid", Long.class, itemId);
    }

    @Test
    void borrarUnIngredienteLoQuitaDeLosProductosYElProductoBorradoLimpiaSuRelacion() throws Exception {
        String sal = createIngredient(ownerA, "Sal");
        String itemId = createItem("Papas", sal);

        mvc.perform(write(delete("/api/v1/admin/ingredients/" + sal), ownerA, "")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/menu-items").cookie(ownerA)).andExpect(jsonPath("$.content[0].ingredients", hasSize(0)));

        String pimienta = createIngredient(ownerA, "Pimienta");
        long version = tenantItemVersion(itemId);
        mvc.perform(write(put("/api/v1/admin/menu-items/" + itemId), ownerA,
                "{\"categoryId\":\"%s\",\"name\":\"Papas\",\"price\":5.00,\"version\":%d,\"ingredientIds\":[\"%s\"]}"
                        .formatted(categoryA.getId(), version, pimienta))).andExpect(status().isOk());
        mvc.perform(write(delete("/api/v1/admin/menu-items/" + itemId), ownerA, "")).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_item_ingredients", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingredients WHERE tenant_id = ?", Long.class, a.getId())).isEqualTo(1);
    }

    @Test
    void elSitioPublicoMuestraLosIngredientesDeCadaProducto() throws Exception {
        String queso = createIngredient(ownerA, "Queso");
        String tomate = createIngredient(ownerA, "Tomate");
        createItem("Pizza", queso, tomate);
        createItem("Agua");

        mvc.perform(get("/api/v1/public/sites/negocio-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].items[?(@.name=='Pizza')].ingredients[0]").value(org.hamcrest.Matchers.hasItem("Queso")))
                .andExpect(jsonPath("$.categories[0].items[?(@.name=='Pizza')].ingredients[1]").value(org.hamcrest.Matchers.hasItem("Tomate")))
                .andExpect(jsonPath("$.categories[0].items[?(@.name=='Agua')].ingredients[0]").isEmpty());
    }
}

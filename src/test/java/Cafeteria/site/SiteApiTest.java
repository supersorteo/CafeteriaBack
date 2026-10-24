package Cafeteria.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import Cafeteria.catalog.Category;
import Cafeteria.catalog.CategoryRepository;
import Cafeteria.catalog.MenuItem;
import Cafeteria.catalog.MenuItemRepository;
import Cafeteria.media.MediaAsset;
import Cafeteria.media.MediaAssetRepository;
import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class SiteApiTest extends IntegrationTestSupport {

    @Autowired
    CategoryRepository categories;
    @Autowired
    MenuItemRepository items;
    @Autowired
    MediaAssetRepository assets;
    @Autowired
    SiteImageRepository siteImages;

    Tenant a;
    Tenant b;
    Cookie ownerA;
    Cookie editorA;

    @BeforeEach
    void fixtures() throws Exception {
        a = tenant("negocio-a");
        b = tenant("negocio-b");
        member("owner@a.test", a, Role.OWNER, true);
        member("editor@a.test", a, Role.EDITOR, true);
        member("owner@b.test", b, Role.OWNER, true);
        ownerA = login("owner@a.test");
        editorA = login("editor@a.test");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Cookie session, String json)
            throws Exception {
        return withXsrf(request.cookie(session), newXsrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String settingsJson(Long version, String whatsapp, String color, String currency, String timezone) {
        return ("{\"name\":\"Café A\",\"whatsappNumber\":%s,\"phone\":\"099 123 456\",\"email\":\"hola@a.test\","
                + "\"address\":\"Calle 1\",\"schedule\":\"Lun a Dom 18-02\",\"currency\":\"%s\",\"locale\":\"es-UY\","
                + "\"timezone\":\"%s\",\"primaryColor\":\"%s\",\"accentColor\":\"#112233\",\"heroTitle\":\"Título\","
                + "\"heroDescription\":\"Desc\",\"aboutText\":\"Nosotros\",\"version\":%s}")
                .formatted(whatsapp == null ? "null" : "\"" + whatsapp + "\"", currency, timezone, color, version);
    }

    private long versionOf(Tenant t) {
        return tenants.findById(t.getId()).orElseThrow().getVersion();
    }

    @Test
    void editorPuedeLeerPeroNoModificarLaConfiguracion() throws Exception {
        mvc.perform(get("/api/v1/admin/site").cookie(editorA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("negocio-a"));

        mvc.perform(write(put("/api/v1/admin/site"), editorA,
                        settingsJson(versionOf(a), "59899111222", "#aabbcc", "UYU", "America/Montevideo")))
                .andExpect(status().isForbidden());

        assertThat(tenants.findById(a.getId()).orElseThrow().getName()).isEqualTo("Negocio negocio-a");
    }

    @Test
    void ownerActualizaLaConfiguracionYSoloLaDeSuNegocio() throws Exception {
        long versionB = versionOf(b);

        mvc.perform(write(put("/api/v1/admin/site"), ownerA,
                        settingsJson(versionOf(a), "59899111222", "#AABBCC", "USD", "America/Argentina/Buenos_Aires")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Café A"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.slug").value("negocio-a"))
                .andExpect(jsonPath("$.version").value(versionOf(a)));

        Tenant stored = tenants.findById(b.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Negocio negocio-b");
        assertThat(stored.getVersion()).isEqualTo(versionB);
    }

    @Test
    void validacionesDeConfiguracion() throws Exception {
        long v = versionOf(a);
        String ok = "America/Montevideo";

        mvc.perform(write(put("/api/v1/admin/site"), ownerA, settingsJson(v, "+59899111222", "#aabbcc", "UYU", ok)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("whatsappNumber")));
        mvc.perform(write(put("/api/v1/admin/site"), ownerA, settingsJson(v, "123", "#aabbcc", "UYU", ok)))
                .andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/v1/admin/site"), ownerA, settingsJson(v, "59899111222", "rojo", "UYU", ok)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("primaryColor")));
        mvc.perform(write(put("/api/v1/admin/site"), ownerA, settingsJson(v, "59899111222", "#aabbcc", "BTC", ok)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("currency")));
        mvc.perform(write(put("/api/v1/admin/site"), ownerA,
                        settingsJson(v, "59899111222", "#aabbcc", "UYU", "Marte/Olympus")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("timezone"));

        assertThat(tenants.findById(a.getId()).orElseThrow().getName()).isEqualTo("Negocio negocio-a");
        assertThat(versionOf(a)).isEqualTo(v);
    }

    @Test
    void versionAntiguaDeConfiguracionDevuelve409() throws Exception {
        long v = versionOf(a);
        mvc.perform(write(put("/api/v1/admin/site"), ownerA, settingsJson(v, "59899111222", "#aabbcc", "UYU", "America/Montevideo")))
                .andExpect(status().isOk());

        mvc.perform(write(put("/api/v1/admin/site"), ownerA, settingsJson(v, "59899111333", "#aabbcc", "UYU", "America/Montevideo")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("version_conflict"));

        assertThat(tenants.findById(a.getId()).orElseThrow().getWhatsappNumber()).isEqualTo("59899111222");
    }

    @Test
    void publicarExigeWhatsappYSoloElOwnerPuede() throws Exception {
        mvc.perform(write(put("/api/v1/admin/site/publication"), ownerA, "{\"published\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("cannot_publish"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("whatsappNumber"));
        assertThat(tenants.findById(a.getId()).orElseThrow().isPublished()).isFalse();

        mvc.perform(write(put("/api/v1/admin/site"), ownerA,
                        settingsJson(versionOf(a), "59899111222", "#aabbcc", "UYU", "America/Montevideo")))
                .andExpect(status().isOk());

        mvc.perform(write(put("/api/v1/admin/site/publication"), editorA, "{\"published\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(write(put("/api/v1/admin/site/publication"), ownerA, "{\"published\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true));
        mvc.perform(write(put("/api/v1/admin/site/publication"), ownerA, "{\"published\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false));
    }

    @Test
    void publicarConVersionAntiguaDevuelve409() throws Exception {
        long v = versionOf(a);
        mvc.perform(write(put("/api/v1/admin/site"), ownerA, settingsJson(v, "59899111222", "#aabbcc", "UYU", "America/Montevideo")))
                .andExpect(status().isOk());

        mvc.perform(write(put("/api/v1/admin/site/publication"), ownerA, "{\"published\":true,\"version\":" + v + "}"))
                .andExpect(status().isConflict());
        assertThat(tenants.findById(a.getId()).orElseThrow().isPublished()).isFalse();
    }

    @Test
    void imagenesDelSitioConHeroNosotrosYGaleria() throws Exception {
        MediaAsset hero = uploadDirect(a);
        MediaAsset about1 = uploadDirect(a);
        MediaAsset gal1 = uploadDirect(a);

        mvc.perform(write(put("/api/v1/admin/site/images"), ownerA,
                        "{\"hero\":\"%s\",\"about\":[\"%s\"],\"gallery\":[{\"mediaId\":\"%s\",\"alt\":\"Terraza\"}]}"
                                .formatted(hero.getId(), about1.getId(), gal1.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.hero.url").value("/media/" + hero.getId()))
                .andExpect(jsonPath("$.images.about[0].id").value(about1.getId().toString()))
                .andExpect(jsonPath("$.images.gallery[0].alt").value("Terraza"));

        mvc.perform(get("/api/v1/admin/site").cookie(editorA))
                .andExpect(jsonPath("$.images.gallery[0].id").value(gal1.getId().toString()));
    }

    @Test
    void reemplazarLasImagenesLimpiaLasQueQuedanLibres() throws Exception {
        MediaAsset vieja = uploadDirect(a);
        MediaAsset nueva = uploadDirect(a);
        mvc.perform(write(put("/api/v1/admin/site/images"), ownerA, "{\"hero\":\"%s\"}".formatted(vieja.getId())))
                .andExpect(status().isOk());

        mvc.perform(write(put("/api/v1/admin/site/images"), ownerA, "{\"hero\":\"%s\"}".formatted(nueva.getId())))
                .andExpect(status().isOk());

        assertThat(assets.findById(vieja.getId())).isEmpty();
        assertThat(Files.exists(uploadRoot().resolve(vieja.getStorageKey()))).isFalse();
        assertThat(assets.findById(nueva.getId())).isPresent();
    }

    @Test
    void imagenesDeOtroNegocioOLimitesExcedidosSeRechazanSinCambios() throws Exception {
        MediaAsset propia = uploadDirect(a);
        MediaAsset ajena = uploadDirect(b);
        mvc.perform(write(put("/api/v1/admin/site/images"), ownerA, "{\"hero\":\"%s\"}".formatted(propia.getId())))
                .andExpect(status().isOk());

        mvc.perform(write(put("/api/v1/admin/site/images"), ownerA, "{\"hero\":\"%s\"}".formatted(ajena.getId())))
                .andExpect(status().isBadRequest());
        String cuatro = "[\"%1$s\",\"%1$s\",\"%1$s\",\"%1$s\"]".formatted(propia.getId());
        mvc.perform(write(put("/api/v1/admin/site/images"), ownerA, "{\"about\":" + cuatro + "}"))
                .andExpect(status().isBadRequest());
        String siete = "{\"mediaId\":\"%s\"}".formatted(propia.getId());
        mvc.perform(write(put("/api/v1/admin/site/images"), ownerA,
                        "{\"gallery\":[" + String.join(",", java.util.Collections.nCopies(7, siete)) + "]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/v1/admin/site/images"), editorA, "{\"hero\":\"%s\"}".formatted(propia.getId())))
                .andExpect(status().isForbidden());

        assertThat(siteImages.findByTenantIdOrderBySlotAscPositionAsc(a.getId())).hasSize(1);
        assertThat(assets.findById(ajena.getId())).isPresent();
    }

    @Test
    void dashboardResumeElNegocio() throws Exception {
        Category cat = categories.save(new Category(a.getId(), "Platos", 0));
        items.save(new MenuItem(a.getId(), cat.getId(), "Activo", null, BigDecimal.ONE, null, 0));
        MenuItem off = new MenuItem(a.getId(), cat.getId(), "Inactivo", null, BigDecimal.ONE, null, 1);
        off.setActive(false);
        items.save(off);
        uploadDirect(a);
        Category ajena = categories.save(new Category(b.getId(), "Otra", 0));
        items.save(new MenuItem(b.getId(), ajena.getId(), "De B", null, BigDecimal.ONE, null, 0));

        mvc.perform(get("/api/v1/admin/dashboard").cookie(editorA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").value(1))
                .andExpect(jsonPath("$.activeItems").value(1))
                .andExpect(jsonPath("$.inactiveItems").value(1))
                .andExpect(jsonPath("$.images").value(1))
                .andExpect(jsonPath("$.published").value(false));
    }

    @Test
    void limitesDeCatalogoPorNegocio() throws Exception {
        for (int i = 0; i < 30; i++) {
            categories.save(new Category(a.getId(), "Cat " + i, i));
        }

        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/categories"),
                        ownerA, "{\"name\":\"Una más\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("limit_reached"));
        assertThat(categories.countByTenantId(a.getId())).isEqualTo(30);
        assertThat(UUID.randomUUID()).isNotNull();
    }
}

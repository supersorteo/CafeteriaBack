package Cafeteria.publicsite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import Cafeteria.catalog.Category;
import Cafeteria.catalog.CategoryRepository;
import Cafeteria.catalog.MenuItem;
import Cafeteria.catalog.MenuItemRepository;
import Cafeteria.media.MediaAsset;
import Cafeteria.site.SiteImage;
import Cafeteria.site.SiteImageRepository;
import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class PublicSiteApiTest extends IntegrationTestSupport {

    @Autowired
    CategoryRepository categories;
    @Autowired
    MenuItemRepository items;
    @Autowired
    SiteImageRepository siteImages;

    Tenant a;
    MediaAsset itemImage;
    MediaAsset heroImage;

    @BeforeEach
    void fixtures() {
        a = tenant("negocio-a");
        a.setPublished(true);
        a.setWhatsappNumber("59899111222");
        a.setHeroTitle("Hola");
        a.setPhone("099 000 111");
        a = tenants.save(a);
        member("owner@a.test", a, Role.OWNER, true);

        Category bebidas = categories.save(new Category(a.getId(), "Bebidas", 0));
        Category vacia = categories.save(new Category(a.getId(), "Vacía", 1));
        itemImage = uploadDirect(a);
        heroImage = uploadDirect(a);

        MenuItem uno = new MenuItem(a.getId(), bebidas.getId(), "Segundo", "d", new BigDecimal("12.50"), "tag", 1);
        uno.setImageId(itemImage.getId());
        items.save(uno);
        items.save(new MenuItem(a.getId(), bebidas.getId(), "Primero", null, new BigDecimal("8.00"), null, 0));
        MenuItem oculto = new MenuItem(a.getId(), bebidas.getId(), "Oculto", null, BigDecimal.ONE, null, 2);
        oculto.setActive(false);
        items.save(oculto);
        siteImages.save(new SiteImage(a.getId(), SiteImage.Slot.HERO, 0, heroImage.getId(), null));
        assertThat(vacia.getId()).isNotNull();
    }

    @Test
    void sitioPublicadoDevuelveContenidoYSoloProductosActivosOrdenados() throws Exception {
        mvc.perform(get("/api/v1/public/sites/negocio-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Negocio negocio-a"))
                .andExpect(jsonPath("$.contact.whatsappNumber").value("59899111222"))
                .andExpect(jsonPath("$.currency").value("UYU"))
                .andExpect(jsonPath("$.theme.primaryColor").value("#e4572e"))
                .andExpect(jsonPath("$.hero.title").value("Hola"))
                .andExpect(jsonPath("$.hero.imageUrl").value("/media/" + heroImage.getId()))
                .andExpect(jsonPath("$.categories[*].name", contains("Bebidas", "Vacía")))
                .andExpect(jsonPath("$.categories[0].items[*].name", contains("Primero", "Segundo")))
                .andExpect(jsonPath("$.categories[0].items[1].imageUrl").value("/media/" + itemImage.getId()))
                .andExpect(jsonPath("$.categories[0].items[0].imageUrl").doesNotExist())
                .andExpect(jsonPath("$.categories[1].items", hasSize(0)));
    }

    @Test
    void elLogoSoloApareceCuandoElNegocioLoSubio() throws Exception {
        mvc.perform(get("/api/v1/public/sites/negocio-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").doesNotExist());

        MediaAsset logo = uploadDirect(a);
        siteImages.save(new SiteImage(a.getId(), SiteImage.Slot.LOGO, 0, logo.getId(), null));

        mvc.perform(get("/api/v1/public/sites/negocio-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value("/media/" + logo.getId()))
                .andExpect(jsonPath("$.hero.imageUrl").value("/media/" + heroImage.getId()));
    }

    @Test
    void laRespuestaPublicaNoExponeDatosInternos() throws Exception {
        String json = mvc.perform(get("/api/v1/public/sites/negocio-a")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json)
                .doesNotContain(a.getId().toString())
                .doesNotContain("owner@a.test")
                .doesNotContain("passwordHash")
                .doesNotContain("password")
                .doesNotContain("tenantId")
                .doesNotContain("version")
                .doesNotContain("createdAt")
                .doesNotContain("published")
                .doesNotContain("Oculto")
                .doesNotContain("storageKey")
                .doesNotContain("sortOrder");
    }

    @Test
    void sitioEnBorradorOInexistenteDevuelveElMismo404() throws Exception {
        tenant("borrador");

        String borrador = mvc.perform(get("/api/v1/public/sites/borrador")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found")).andReturn().getResponse().getContentAsString();
        String inexistente = mvc.perform(get("/api/v1/public/sites/no-existe")).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        for (String field : new String[] { "$.status", "$.code", "$.title", "$.detail" }) {
            assertThat((Object) com.jayway.jsonpath.JsonPath.read(borrador, field))
                    .isEqualTo(com.jayway.jsonpath.JsonPath.read(inexistente, field));
        }
    }

    @Test
    void despublicarOcultaElSitioPeroLasImagenesYaPublicadasSiguenAccesibles() throws Exception {
        Cookie owner = login("owner@a.test");

        mvc.perform(withXsrf(put("/api/v1/admin/site/publication").cookie(owner), newXsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"published\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/public/sites/negocio-a")).andExpect(status().isNotFound());
        mvc.perform(get("/media/" + itemImage.getId())).andExpect(status().isOk());
    }

    @Test
    void loCambiadoEnElPanelSeVeAlRecargar() throws Exception {
        Category cat = categories.findByTenantIdOrderBySortOrderAscIdAsc(a.getId()).get(1);
        items.save(new MenuItem(a.getId(), cat.getId(), "Nuevo", null, BigDecimal.TEN, null, 0));

        mvc.perform(get("/api/v1/public/sites/negocio-a"))
                .andExpect(jsonPath("$.categories[1].items[0].name").value("Nuevo"));
    }

    @Test
    void elSitioPublicoNoRequiereSesionPeroElPanelSi() throws Exception {
        mvc.perform(get("/api/v1/public/sites/negocio-a")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/site")).andExpect(status().isUnauthorized());
    }
}

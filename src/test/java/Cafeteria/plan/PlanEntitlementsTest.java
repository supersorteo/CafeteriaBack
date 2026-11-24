package Cafeteria.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Collections;
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
import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class PlanEntitlementsTest extends IntegrationTestSupport {

    @Autowired
    CategoryRepository categories;
    @Autowired
    MenuItemRepository items;

    Tenant basic;
    Tenant pro;
    Cookie basicOwner;
    Cookie proOwner;
    Category basicCategory;
    Category proCategory;

    @BeforeEach
    void fixtures() throws Exception {
        basic = withPlan(tenant("basico"), "EMPRENDEDOR");
        pro = withPlan(tenant("pro"), "PROFESIONAL");
        member("owner@basico.test", basic, Role.OWNER, true);
        member("owner@pro.test", pro, Role.OWNER, true);
        basicOwner = login("owner@basico.test");
        proOwner = login("owner@pro.test");
        basicCategory = categories.save(new Category(basic.getId(), "Platos", 0));
        proCategory = categories.save(new Category(pro.getId(), "Platos", 0));
        jdbc.update("UPDATE tenants SET published = TRUE, whatsapp_number = '59899111222'");
    }

    private Tenant withPlan(Tenant tenant, String code) {
        tenant.setPlanCode(code);
        return tenants.save(tenant);
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Cookie session, String json) throws Exception {
        return withXsrf(request.cookie(session), newXsrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private void fill(Tenant tenant, Category category, int count) {
        for (int i = 0; i < count; i++) {
            items.save(new MenuItem(tenant.getId(), category.getId(), "Plato " + i, null, BigDecimal.ONE, null, i));
        }
    }

    private String item(Category category, String name) {
        return "{\"categoryId\":\"%s\",\"name\":\"%s\",\"price\":5.00}".formatted(category.getId(), name);
    }

    @Test
    void elEmprendedorLlegaASusCincuentaPlatosYElProfesionalAlosCien() throws Exception {
        fill(basic, basicCategory, 50);
        fill(pro, proCategory, 50);

        mvc.perform(write(post("/api/v1/admin/menu-items"), basicOwner, item(basicCategory, "Uno más")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_limit_reached"));
        mvc.perform(write(post("/api/v1/admin/menu-items"), proOwner, item(proCategory, "Uno más"))).andExpect(status().isCreated());

        assertThat(items.countByTenantId(basic.getId())).isEqualTo(50);
        assertThat(items.countByTenantId(pro.getId())).isEqualTo(51);
    }

    @Test
    void elNegocioDemoNoTieneTopesDePlan() throws Exception {
        Tenant demo = tenants.save(tenant("la-terraza"));
        member("owner@demo.test", demo, Role.OWNER, true);
        Category category = categories.save(new Category(demo.getId(), "Platos", 0));
        fill(demo, category, 105);
        demo.setPlanExpiresAt(java.time.Instant.now().minusSeconds(86400));
        tenants.save(demo);

        mvc.perform(write(post("/api/v1/admin/menu-items"), login("owner@demo.test"), item(category, "Uno más")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/auth/me").cookie(login("owner@demo.test")))
                .andExpect(jsonPath("$.plan.status").value("ACTIVE")).andExpect(jsonPath("$.plan.expiresAt").doesNotExist());
    }

    @Test
    void loSinPlanAsignadoSeTrataComoProfesional() throws Exception {
        Tenant legacy = tenants.save(tenant("antiguo"));
        member("owner@antiguo.test", legacy, Role.OWNER, true);
        Category category = categories.save(new Category(legacy.getId(), "Platos", 0));
        fill(legacy, category, 60);

        mvc.perform(write(post("/api/v1/admin/menu-items"), login("owner@antiguo.test"), item(category, "Uno más")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/auth/me").cookie(login("owner@antiguo.test")))
                .andExpect(jsonPath("$.plan.planCode").value("PROFESIONAL"));
    }

    @Test
    void loslimitesSeEditanDesdeLosDatosSinTocarElCodigo() throws Exception {
        jdbc.update("UPDATE billing_plans SET max_menu_items = 2 WHERE code = 'EMPRENDEDOR'");
        fill(basic, basicCategory, 2);

        mvc.perform(write(post("/api/v1/admin/menu-items"), basicOwner, item(basicCategory, "Tercero")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_limit_reached"));
        jdbc.update("UPDATE billing_plans SET max_menu_items = 3 WHERE code = 'EMPRENDEDOR'");
        mvc.perform(write(post("/api/v1/admin/menu-items"), basicOwner, item(basicCategory, "Tercero"))).andExpect(status().isCreated());
    }

    @Test
    void siElNegocioSuperaSuLimiteElSitioPublicoMuestraSoloLosPrimerosYNoSeBorraNada() throws Exception {
        String[] names = { "Primero", "Segundo", "Tercero" };
        for (int i = 0; i < names.length; i++) {
            items.saveAndFlush(new MenuItem(basic.getId(), basicCategory.getId(), names[i], null, BigDecimal.ONE, null, i));
            Thread.sleep(5);
        }
        jdbc.update("UPDATE billing_plans SET max_menu_items = 2 WHERE code = 'EMPRENDEDOR'");

        mvc.perform(get("/api/v1/public/sites/basico"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categories[0].items", hasSize(2)))
                .andExpect(jsonPath("$.categories[0].items[0].name").value("Primero"))
                .andExpect(jsonPath("$.categories[0].items[1].name").value("Segundo"));
        assertThat(items.countByTenantId(basic.getId())).isEqualTo(3);
    }

    @Test
    void laMarcaCompletaEsDelProfesionalYElSitioIgnoraLaMarcaAvanzadaDeUnPlanQueNoLaIncluye() throws Exception {
        String json = "{\"name\":\"Café\",\"currency\":\"UYU\",\"locale\":\"es-UY\",\"timezone\":\"America/Montevideo\","
                + "\"primaryColor\":\"#AABBCC\",\"accentColor\":\"#112233\",\"footerColor\":\"#102030\",\"fontFamily\":\"inter\",\"version\":%d}";

        mvc.perform(write(put("/api/v1/admin/site"), basicOwner, json.formatted(tenants.findById(basic.getId()).orElseThrow().getVersion())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_feature_unavailable"));
        mvc.perform(write(put("/api/v1/admin/site"), proOwner, json.formatted(tenants.findById(pro.getId()).orElseThrow().getVersion())))
                .andExpect(status().isOk());

        jdbc.update("UPDATE tenants SET footer_color = '#102030', font_family = 'inter' WHERE slug = 'basico'");
        mvc.perform(get("/api/v1/public/sites/basico"))
                .andExpect(jsonPath("$.theme.primaryColor").exists()).andExpect(jsonPath("$.theme.footerColor").doesNotExist())
                .andExpect(jsonPath("$.theme.fontFamily").doesNotExist());
        mvc.perform(get("/api/v1/public/sites/pro")).andExpect(jsonPath("$.theme.footerColor").value("#102030"));
    }

    @Test
    void laGaleriaAdmiteSeisFotosEnElEmprendedorYQuinceEnElProfesional() throws Exception {
        MediaAsset own = uploadDirect(basic);
        MediaAsset proOwn = uploadDirect(pro);
        String seven = gallery(own.getId(), 7);
        String six = gallery(own.getId(), 6);

        mvc.perform(write(put("/api/v1/admin/site/images"), basicOwner, "{\"gallery\":" + seven + "}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_limit_reached"));
        mvc.perform(write(put("/api/v1/admin/site/images"), basicOwner, "{\"gallery\":" + six + "}")).andExpect(status().isOk());
        mvc.perform(write(put("/api/v1/admin/site/images"), proOwner, "{\"gallery\":" + gallery(proOwn.getId(), 15) + "}"))
                .andExpect(status().isOk());
    }

    private String gallery(UUID mediaId, int count) {
        String one = "{\"mediaId\":\"%s\"}".formatted(mediaId);
        return "[" + String.join(",", Collections.nCopies(count, one)) + "]";
    }

    @Test
    void elCodigoLlevaElPlanYAlRegistrarseElNegocioLoHereda() throws Exception {
        String code = plans.generateBatch("Emprendedores", 1, 1, null, "test", "EMPRENDEDOR").codes().get(0);

        postJson("/api/v1/auth/register", "{\"email\":\"nuevo@cafe.test\",\"password\":\"Password-12345\",\"businessName\":\"Nuevo\","
                + "\"slug\":\"nuevo\",\"activationCode\":\"%s\"}".formatted(code)).andExpect(status().isAccepted());

        assertThat(tenants.findBySlug("nuevo").orElseThrow().getPlanCode()).isEqualTo("EMPRENDEDOR");
        assertThat(jdbc.queryForObject("SELECT plan_code FROM activation_codes WHERE code = ?", String.class, code)).isEqualTo("EMPRENDEDOR");
    }

    @Test
    void unPlanInexistenteSeRechazaAlGenerarCodigosYRenovarConOtroPlanCambiaElPlan() throws Exception {
        org.junit.jupiter.api.Assertions.assertThrows(Cafeteria.shared.ApiException.class,
                () -> plans.generateBatch("x", 1, 1, null, "test", "INEXISTENTE"));

        String upgrade = plans.generateBatch("Pro", 1, 1, null, "test", "PROFESIONAL").codes().get(0);
        mvc.perform(write(post("/api/v1/admin/plan/redeem"), basicOwner, "{\"code\":\"%s\"}".formatted(upgrade)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planCode").value("PROFESIONAL"))
                .andExpect(jsonPath("$.limits.maxMenuItems").value(100));
        assertThat(tenants.findById(basic.getId()).orElseThrow().getPlanCode()).isEqualTo("PROFESIONAL");
    }

    @Test
    void meYElPlanDevuelvenLosLimitesYElUsoActual() throws Exception {
        fill(basic, basicCategory, 3);

        mvc.perform(get("/api/v1/auth/me").cookie(basicOwner))
                .andExpect(jsonPath("$.plan.planCode").value("EMPRENDEDOR"))
                .andExpect(jsonPath("$.plan.limits.maxMenuItems").value(50))
                .andExpect(jsonPath("$.plan.limits.maxActiveCoupons").value(15))
                .andExpect(jsonPath("$.plan.limits.maxGallery").value(6))
                .andExpect(jsonPath("$.plan.limits.fullBranding").value(false))
                .andExpect(jsonPath("$.plan.usage.menuItems").value(3))
                .andExpect(jsonPath("$.plan.usage.activeCoupons").value(0))
                .andExpect(jsonPath("$.plan.qrCustomization").value(true));
        mvc.perform(get("/api/v1/admin/plan").cookie(proOwner))
                .andExpect(jsonPath("$.limits.maxMenuItems").value(100)).andExpect(jsonPath("$.limits.fullBranding").value(true));
    }
}

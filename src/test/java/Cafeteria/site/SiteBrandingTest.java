package Cafeteria.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class SiteBrandingTest extends IntegrationTestSupport {

    Tenant tenant;
    Cookie owner;
    Cookie editor;

    @BeforeEach
    void fixtures() throws Exception {
        tenant = tenant("marca");
        tenant.setPublished(true);
        tenant = tenants.save(tenant);
        member("owner@marca.test", tenant, Role.OWNER, true);
        member("editor@marca.test", tenant, Role.EDITOR, true);
        owner = login("owner@marca.test");
        editor = login("editor@marca.test");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Cookie session, String json) throws Exception {
        return withXsrf(request.cookie(session), newXsrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String settings(String extra) {
        long version = tenants.findById(tenant.getId()).orElseThrow().getVersion();
        return ("{\"name\":\"Café Marca\",\"currency\":\"UYU\",\"locale\":\"es-UY\",\"timezone\":\"America/Montevideo\","
                + "\"primaryColor\":\"#AABBCC\",\"accentColor\":\"#112233\",\"version\":%d%s}").formatted(version, extra);
    }

    private String qr(String dot, String background, boolean logo) {
        return ("{\"dotType\":\"%s\",\"cornerType\":\"dot\",\"dotColor\":\"#111827\",\"backgroundMode\":\"%s\","
                + "\"backgroundColor\":\"#ffffff\",\"useLogo\":%s}").formatted(dot, background, logo);
    }

    private void withPlan(Integer months) {
        Tenant t = tenants.findById(tenant.getId()).orElseThrow();
        t.setPlanMonths(months);
        tenants.save(t);
    }

    @Test
    void laMarcaSeGuardaSeDevuelveEnElAdminYLlegaAlSitioPublico() throws Exception {
        String brand = ",\"businessTitleColor\":\"#2a1420\",\"backgroundColor\":\"#fff6ec\",\"footerColor\":\"#102030\","
                + "\"headingColor\":\"#3d1d2e\",\"paragraphColor\":\"#5b4150\",\"whatsappColor\":\"#25d366\","
                + "\"marqueeColor\":\"#3d1d2e\",\"fontFamily\":\"garamond\"";

        mvc.perform(write(put("/api/v1/admin/site"), owner, settings(brand)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.footerColor").value("#102030"))
                .andExpect(jsonPath("$.businessTitleColor").value("#2A1420"))
                .andExpect(jsonPath("$.fontFamily").value("garamond"));

        mvc.perform(get("/api/v1/admin/site").cookie(owner)).andExpect(jsonPath("$.marqueeColor").value("#3D1D2E"));
        mvc.perform(get("/api/v1/public/sites/marca"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme.primaryColor").value("#AABBCC"))
                .andExpect(jsonPath("$.theme.footerColor").value("#102030"))
                .andExpect(jsonPath("$.theme.whatsappColor").value("#25D366"))
                .andExpect(jsonPath("$.theme.fontFamily").value("garamond"));
    }

    @Test
    void sinCamposDeMarcaTodoQuedaVacioYElFrontendUsaSusValoresPorDefecto() throws Exception {
        mvc.perform(write(put("/api/v1/admin/site"), owner, settings(""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.footerColor").doesNotExist())
                .andExpect(jsonPath("$.fontFamily").doesNotExist());
        mvc.perform(get("/api/v1/public/sites/marca"))
                .andExpect(jsonPath("$.theme.footerColor").doesNotExist())
                .andExpect(jsonPath("$.theme.fontFamily").doesNotExist());
    }

    @Test
    void unValorVacioBorraLaPersonalizacionYVuelveAlPorDefecto() throws Exception {
        mvc.perform(write(put("/api/v1/admin/site"), owner, settings(",\"footerColor\":\"#102030\",\"fontFamily\":\"inter\"")))
                .andExpect(status().isOk());
        mvc.perform(write(put("/api/v1/admin/site"), owner, settings(",\"footerColor\":\"\",\"fontFamily\":null"))).andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/v1/admin/site"), owner, settings(",\"footerColor\":null,\"fontFamily\":null"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.footerColor").doesNotExist());
    }

    @Test
    void colorOFuenteInvalidosSeRechazanConElCampoEnElError() throws Exception {
        mvc.perform(write(put("/api/v1/admin/site"), owner, settings(",\"footerColor\":\"rojo\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[*].field", hasItem("footerColor")));
        mvc.perform(write(put("/api/v1/admin/site"), owner, settings(",\"marqueeColor\":\"#12345\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[*].field", hasItem("marqueeColor")));
        mvc.perform(write(put("/api/v1/admin/site"), owner, settings(",\"fontFamily\":\"comic-sans\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[*].field", hasItem("fontFamily")));

        assertThat(tenants.findById(tenant.getId()).orElseThrow().getFooterColor()).isNull();
    }

    @Test
    void elEditorNoPuedeCambiarLaMarca() throws Exception {
        mvc.perform(write(put("/api/v1/admin/site"), editor, settings(",\"footerColor\":\"#102030\"")))
                .andExpect(status().isForbidden());
    }

    @Test
    void elPlanDeTresMesesOMasPersonalizaElQrYSeGuardaPorNegocio() throws Exception {
        for (int months : new int[] { 3, 6, 12 }) {
            withPlan(months);
            mvc.perform(write(put("/api/v1/admin/qr"), owner, qr("rounded", "gradient", true)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dotType").value("rounded"))
                    .andExpect(jsonPath("$.dotColor").value("#111827"))
                    .andExpect(jsonPath("$.backgroundColor").value("#FFFFFF"))
                    .andExpect(jsonPath("$.useLogo").value(true))
                    .andExpect(jsonPath("$.customizable").value(true));
        }

        mvc.perform(get("/api/v1/admin/qr").cookie(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backgroundMode").value("gradient"));
    }

    @Test
    void elPlanDeUnMesNoPuedePersonalizarElQr() throws Exception {
        withPlan(1);

        mvc.perform(write(put("/api/v1/admin/qr"), owner, qr("rounded", "solid", false)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_feature_unavailable"));
        mvc.perform(get("/api/v1/admin/qr").cookie(owner))
                .andExpect(jsonPath("$.customizable").value(false)).andExpect(jsonPath("$.dotType").doesNotExist());

        assertThat(tenants.findById(tenant.getId()).orElseThrow().getQrDotType()).isNull();
    }

    @Test
    void elDisenoDelQrValidaSusOpcionesYSoloLoEditaElOwner() throws Exception {
        withPlan(3);

        mvc.perform(write(put("/api/v1/admin/qr"), owner, qr("triangulo", "solid", false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[*].field", hasItem("dotType")));
        mvc.perform(write(put("/api/v1/admin/qr"), owner, qr("dots", "neon", false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[*].field", hasItem("backgroundMode")));
        mvc.perform(write(put("/api/v1/admin/qr"), owner, "{\"dotType\":\"dots\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/v1/admin/qr"), editor, qr("dots", "solid", false))).andExpect(status().isForbidden());
    }

    @Test
    void conElPlanVencidoElQrNoSePuedeCambiar() throws Exception {
        withPlan(6);
        Tenant t = tenants.findById(tenant.getId()).orElseThrow();
        t.setPlanExpiresAt(java.time.Instant.now().minusSeconds(60));
        tenants.save(t);

        mvc.perform(write(put("/api/v1/admin/qr"), owner, qr("dots", "solid", false)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_expired"));
    }

    @Test
    void laDemoSinPlanNoTieneRestriccionesDeQr() throws Exception {
        withPlan(null);

        mvc.perform(get("/api/v1/admin/qr").cookie(owner)).andExpect(jsonPath("$.customizable").value(true));
    }
}

package Cafeteria.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class CouponsApiTest extends IntegrationTestSupport {

    static final ZoneId ZONE = ZoneId.of("America/Montevideo");

    Tenant basic;
    Tenant pro;
    Cookie basicOwner;
    Cookie basicEditor;
    Cookie proOwner;

    @BeforeEach
    void fixtures() throws Exception {
        basic = plan(tenant("basico"), "EMPRENDEDOR");
        pro = plan(tenant("pro"), "PROFESIONAL");
        member("owner@basico.test", basic, Role.OWNER, true);
        member("editor@basico.test", basic, Role.EDITOR, true);
        member("owner@pro.test", pro, Role.OWNER, true);
        basicOwner = login("owner@basico.test");
        basicEditor = login("editor@basico.test");
        proOwner = login("owner@pro.test");
        jdbc.update("UPDATE tenants SET published = TRUE, timezone = 'America/Montevideo'");
    }

    private Tenant plan(Tenant tenant, String code) {
        tenant.setPlanCode(code);
        return tenants.save(tenant);
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Cookie session, String json) throws Exception {
        return withXsrf(request.cookie(session), newXsrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String coupon(String code, String extra) {
        return "{\"code\":\"%s\",\"type\":\"DESCUENTO\",\"title\":\"20%% en bebidas\",\"benefit\":\"Presenta el código\"%s}"
                .formatted(code, extra);
    }

    private String create(Cookie session, String code, String extra) throws Exception {
        String body = mvc.perform(write(post("/api/v1/admin/coupons"), session, coupon(code, extra)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }

    private long version(String id) {
        return jdbc.queryForObject("SELECT version FROM coupons WHERE id = ?::uuid", Long.class, id);
    }

    @Test
    void secreaEditaYBorraUnCuponNormalizandoElCodigoYElEditorTambienPuede() throws Exception {
        String id = create(basicOwner, "verano-20", "");

        mvc.perform(get("/api/v1/admin/coupons").cookie(basicEditor))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].code").value("VERANO-20")).andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].counted").value(true));
        mvc.perform(write(put("/api/v1/admin/coupons/" + id), basicEditor,
                coupon("VERANO-20", ",\"version\":" + version(id)).replace("20%", "30%")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("30% en bebidas"));
        mvc.perform(write(delete("/api/v1/admin/coupons/" + id), basicOwner, "")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/coupons").cookie(basicOwner)).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void validaElCodigoLosDatosLasFechasYElCodigoUnicoPorNegocio() throws Exception {
        create(basicOwner, "UNICO", "");
        create(proOwner, "UNICO", "");

        mvc.perform(write(post("/api/v1/admin/coupons"), basicOwner, coupon("unico", "")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate_coupon_code"));
        mvc.perform(write(post("/api/v1/admin/coupons"), basicOwner, coupon("a!", ""))).andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/v1/admin/coupons"), basicOwner, coupon("FECHAS", ",\"startsOn\":\"2026-12-10\",\"endsOn\":\"2026-12-01\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/v1/admin/coupons"), basicOwner, "{\"code\":\"TIPO\",\"type\":\"MAGIA\",\"title\":\"x\",\"benefit\":\"y\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ElLimiteCuentaSoloLosCuponesActivosALaVez() throws Exception {
        jdbc.update("UPDATE billing_plans SET max_active_coupons = 2 WHERE code = 'EMPRENDEDOR'");
        String vencido = create(basicOwner, "VENCIDO", ",\"endsOn\":\"" + LocalDate.now(ZONE).minusDays(1) + "\"");
        String apagado = create(basicOwner, "APAGADO", ",\"active\":false");
        create(basicOwner, "UNO", "");
        create(basicOwner, "DOS", "");

        mvc.perform(write(post("/api/v1/admin/coupons"), basicOwner, coupon("TRES", "")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_limit_reached"));
        mvc.perform(write(put("/api/v1/admin/coupons/" + apagado), basicOwner, coupon("APAGADO", ",\"active\":true,\"version\":" + version(apagado))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_limit_reached"));
        mvc.perform(get("/api/v1/auth/me").cookie(basicOwner)).andExpect(jsonPath("$.plan.usage.activeCoupons").value(2));
        mvc.perform(write(post("/api/v1/admin/coupons"), basicOwner, coupon("OTRO-APAGADO", ",\"active\":false")))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT status_count FROM (SELECT count(*) AS status_count FROM coupons WHERE tenant_id = ?) c",
                Long.class, basic.getId())).isEqualTo(5);
        assertThat(vencido).isNotBlank();
    }

    @Test
    void ElEmprendedorNoUsaHorariosUsosNiDestacadoYElProfesionalSi() throws Exception {
        String advanced = ",\"weekdays\":\"135\",\"timeFrom\":\"17:00:00\",\"timeTo\":\"20:00:00\",\"maxRedemptions\":50,\"featured\":true";

        mvc.perform(write(post("/api/v1/admin/coupons"), basicOwner, coupon("HAPPY", advanced)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_feature_unavailable"));
        mvc.perform(write(post("/api/v1/admin/coupons"), proOwner, coupon("HAPPY", advanced)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.weekdays").value("135"))
                .andExpect(jsonPath("$.maxRedemptions").value(50)).andExpect(jsonPath("$.featured").value(true));
        mvc.perform(write(post("/api/v1/admin/coupons"), proOwner, coupon("SOLO-HORA", ",\"timeFrom\":\"17:00:00\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void elCanjeSumaUsoRespetaElTopeYEsSoloDelProfesional() throws Exception {
        String id = create(proOwner, "POCOS", ",\"maxRedemptions\":2");
        String plain = create(basicOwner, "BASICO", "");

        mvc.perform(write(post("/api/v1/admin/coupons/" + id + "/redeem"), proOwner, "")).andExpect(jsonPath("$.redemptions").value(1));
        mvc.perform(write(post("/api/v1/admin/coupons/" + id + "/redeem"), proOwner, ""))
                .andExpect(jsonPath("$.redemptions").value(2)).andExpect(jsonPath("$.status").value("EXHAUSTED"));
        mvc.perform(write(post("/api/v1/admin/coupons/" + id + "/redeem"), proOwner, ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("coupon_exhausted"));
        mvc.perform(write(post("/api/v1/admin/coupons/" + plain + "/redeem"), basicOwner, ""))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_feature_unavailable"));
        mvc.perform(get("/api/v1/auth/me").cookie(proOwner)).andExpect(jsonPath("$.plan.usage.activeCoupons").value(0));
    }

    @Test
    void conVersionVencidaElCambioSeRechazaYUnCuponAjenoNoSeToca() throws Exception {
        String id = create(basicOwner, "VER", "");
        long stale = version(id);
        mvc.perform(write(put("/api/v1/admin/coupons/" + id), basicOwner, coupon("VER", ",\"version\":" + stale))).andExpect(status().isOk());

        mvc.perform(write(put("/api/v1/admin/coupons/" + id), basicOwner, coupon("VER", ",\"version\":" + stale)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version_conflict"));
        mvc.perform(write(put("/api/v1/admin/coupons/" + id), proOwner, coupon("VER", ",\"version\":" + version(id)))).andExpect(status().isNotFound());
        mvc.perform(write(delete("/api/v1/admin/coupons/" + id), proOwner, "")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM coupons WHERE tenant_id = ?", Long.class, basic.getId())).isEqualTo(1);
    }

    @Test
    void elSitioPublicoMuestraSoloLosCuponesVigentesYLosAjustaAlPlan() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        create(basicOwner, "ACTIVO", "");
        create(basicOwner, "APAGADO", ",\"active\":false");
        create(basicOwner, "VENCIDO", ",\"endsOn\":\"" + today.minusDays(1) + "\"");
        create(basicOwner, "FUTURO", ",\"startsOn\":\"" + today.plusDays(2) + "\"");
        create(basicOwner, "HASTA-HOY", ",\"endsOn\":\"" + today + "\"");

        mvc.perform(get("/api/v1/public/sites/basico"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.coupons", hasSize(2)))
                .andExpect(jsonPath("$.coupons[0].code").value("ACTIVO")).andExpect(jsonPath("$.coupons[1].code").value("HASTA-HOY"));

        jdbc.update("UPDATE billing_plans SET max_active_coupons = 1 WHERE code = 'EMPRENDEDOR'");
        mvc.perform(get("/api/v1/public/sites/basico")).andExpect(jsonPath("$.coupons", hasSize(1)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM coupons WHERE tenant_id = ?", Long.class, basic.getId())).isEqualTo(5);
    }

    @Test
    void ElProfesionalFiltraPorDiaHorarioYUsosYDestacaLosPrimeros() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZONE);
        int today = now.getDayOfWeek().getValue();
        String otherDay = String.valueOf(today == 7 ? 1 : today + 1);
        boolean morning = now.getHour() < 12;
        String outside = morning ? "\"timeFrom\":\"13:00:00\",\"timeTo\":\"14:00:00\"" : "\"timeFrom\":\"04:00:00\",\"timeTo\":\"05:00:00\"";
        String inside = morning ? "\"timeFrom\":\"00:00:00\",\"timeTo\":\"11:59:00\"" : "\"timeFrom\":\"12:00:00\",\"timeTo\":\"23:59:00\"";

        create(proOwner, "HOY", ",\"weekdays\":\"" + today + "\"");
        create(proOwner, "OTRO-DIA", ",\"weekdays\":\"" + otherDay + "\"");
        create(proOwner, "FUERA-HORA", "," + outside);
        create(proOwner, "EN-HORA", "," + inside);
        create(proOwner, "AGOTADO", ",\"maxRedemptions\":1");
        create(proOwner, "DESTACADO", ",\"featured\":true");
        String exhausted = jdbc.queryForObject("SELECT id::text FROM coupons WHERE code = 'AGOTADO'", String.class);
        mvc.perform(write(post("/api/v1/admin/coupons/" + exhausted + "/redeem"), proOwner, "")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/public/sites/pro"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.coupons", hasSize(3)))
                .andExpect(jsonPath("$.coupons[0].code").value("DESTACADO")).andExpect(jsonPath("$.coupons[0].featured").value(true))
                .andExpect(jsonPath("$.coupons[1].code").value("HOY")).andExpect(jsonPath("$.coupons[1].schedule").exists())
                .andExpect(jsonPath("$.coupons[2].code").value("EN-HORA"));
    }

    @Test
    void sinPlanProfesionalSeIgnoranLosHorariosGuardadosAlBajarDePlan() throws Exception {
        create(proOwner, "SOLO-OTRO-DIA", ",\"weekdays\":\"" + (LocalDate.now(ZONE).getDayOfWeek().getValue() == 7 ? 1 : LocalDate.now(ZONE).getDayOfWeek().getValue() + 1) + "\"");
        mvc.perform(get("/api/v1/public/sites/pro")).andExpect(jsonPath("$.coupons", hasSize(0)));

        jdbc.update("UPDATE tenants SET plan_code = 'EMPRENDEDOR' WHERE slug = 'pro'");

        mvc.perform(get("/api/v1/public/sites/pro")).andExpect(jsonPath("$.coupons", hasSize(1)))
                .andExpect(jsonPath("$.coupons[0].schedule").doesNotExist()).andExpect(jsonPath("$.coupons[0].featured").value(false));
    }

    @Test
    void elTopeDeCuponesGuardadosYElBorradoDeUnNegocioLimpianSusDatos() throws Exception {
        for (int i = 0; i < 100; i++) {
            jdbc.update("INSERT INTO coupons (id, tenant_id, code, type, title, benefit, active) VALUES (gen_random_uuid(), ?, ?, 'OTRO', 't', 'b', FALSE)",
                    basic.getId(), "C" + i + "XX");
        }
        mvc.perform(write(post("/api/v1/admin/coupons"), basicOwner, coupon("NUEVO", ",\"active\":false")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("limit_reached"));
    }
}

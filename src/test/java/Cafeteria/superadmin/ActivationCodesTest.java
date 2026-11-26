package Cafeteria.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class ActivationCodesTest extends IntegrationTestSupport {

    static final Pattern CODE = Pattern.compile("es: (\\d{6})");
    static final Pattern FORMAT = Pattern.compile("^DLR-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$");

    @Autowired
    SuperAdminAuthService auth;

    Cookie sa;

    @BeforeEach
    void superAdmin() throws Exception {
        auth.resetLockout();
        auth.usePenalty(0);
        auth.useClock(Clock.systemUTC());
        MvcResult pending = mvc.perform(withXsrf(post("/api/v1/superadmin/auth/login"), newXsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"root@plataforma.test\",\"password\":\"Super-Clave-De-Prueba-1\"}")).andReturn();
        Matcher matcher = CODE.matcher(mail.last().body());
        assertThat(matcher.find()).isTrue();
        Cookie session = pending.getResponse().getCookie("SA_SESSION");
        MvcResult done = mvc.perform(withXsrf(post("/api/v1/superadmin/auth/verify").cookie(session), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(matcher.group(1)))).andReturn();
        Cookie renewed = done.getResponse().getCookie("SA_SESSION");
        sa = renewed != null ? renewed : session;
    }

    private String registerJson(String email, String slug, String code) {
        return "{\"email\":\"%s\",\"password\":\"Password-12345\",\"businessName\":\"Café %s\",\"slug\":\"%s\",\"activationCode\":\"%s\"}"
                .formatted(email, slug, slug, code);
    }

    private Tenant ownerWithPlan(String slug, Instant expires) throws Exception {
        Tenant tenant = tenant(slug);
        tenant.setPlanExpiresAt(expires);
        tenant = tenants.save(tenant);
        member("owner@" + slug + ".test", tenant, Role.OWNER, true);
        member("editor@" + slug + ".test", tenant, Role.EDITOR, true);
        return tenant;
    }

    private MvcResult batch(int months, int quantity) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/superadmin/codes/batches").cookie(sa), newXsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Lote\",\"durationMonths\":%d,\"quantity\":%d}".formatted(months, quantity))).andReturn();
    }

    private String planPut(Cookie session, String path, String json) throws Exception {
        return mvc.perform(withXsrf(put(path).cookie(session), newXsrf()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void generarUnLoteCreaCodigosUnicosConFormatoYDuracion() throws Exception {
        MvcResult result = batch(3, 40);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        Matcher matcher = Pattern.compile("DLR-[A-Z0-9-]{9}").matcher(body);
        List<String> codes = new ArrayList<>();
        while (matcher.find()) {
            codes.add(matcher.group());
        }
        assertThat(codes).hasSize(40).doesNotHaveDuplicates().allMatch(c -> FORMAT.matcher(c).matches());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activation_codes WHERE duration_months = 3 AND status = 'AVAILABLE'",
                Long.class)).isEqualTo(40);
        assertThat(batch(2, 5).getResponse().getStatus()).isEqualTo(400);
        assertThat(batch(1, 501).getResponse().getStatus()).isEqualTo(400);
        assertThat(batch(12, 0).getResponse().getStatus()).isEqualTo(400);
        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/batches"), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationMonths\":1,\"quantity\":1}")).andExpect(status().isUnauthorized());
    }

    @Test
    void elRegistroConCodigoCreaElNegocioConVencimientoYConsumeElCodigo() throws Exception {
        String code = newCode(3);

        postJson("/api/v1/auth/register", registerJson("nuevo@cafe.test", "cafe-nuevo", code)).andExpect(status().isAccepted());

        Instant expires = tenants.findBySlug("cafe-nuevo").orElseThrow().getPlanExpiresAt();
        assertThat(expires).isBetween(Instant.now().plus(88, ChronoUnit.DAYS), Instant.now().plus(93, ChronoUnit.DAYS));
        assertThat(jdbc.queryForObject("SELECT status FROM activation_codes WHERE code = ?", String.class, code)).isEqualTo("REDEEMED");
        assertThat(jdbc.queryForObject("SELECT redeemed_by_email FROM activation_codes WHERE code = ?", String.class, code))
                .isEqualTo("nuevo@cafe.test");
        assertThat(tenants.findBySlug("cafe-nuevo").orElseThrow().getPlanMonths()).isEqualTo(3);

        postJson("/api/v1/auth/register", registerJson("otro@cafe.test", "cafe-otro", code))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_code"));
        assertThat(tenants.findBySlug("cafe-otro")).isEmpty();
    }

    @Test
    void sinCodigoConCodigoInventadoOUsadoNoSeCreaNada() throws Exception {
        for (String code : new String[] { "", "DLR-AAAA-BBBB", "no-es-un-codigo", "DLR-0000-1111" }) {
            postJson("/api/v1/auth/register", registerJson("a@cafe.test", "cafe-a", code)).andExpect(status().isBadRequest());
        }
        String disabled = newCode(1);
        jdbc.update("UPDATE activation_codes SET status = 'DISABLED' WHERE code = ?", disabled);
        postJson("/api/v1/auth/register", registerJson("a@cafe.test", "cafe-a", disabled))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_code"));

        assertThat(count("users")).isZero();
        assertThat(count("tenants")).isZero();
        postJson("/api/v1/auth/register", registerJson("a@cafe.test", "cafe-a", "dlr 2222 3333"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void elCodigoValidoNoDelataSiUnCorreoYaExisteYNoSeConsume() throws Exception {
        member("existe@cafe.test", tenant("cafe-uno"), Role.OWNER, true);
        String code = newCode(1);

        postJson("/api/v1/auth/register", registerJson("existe@cafe.test", "cafe-dos", code)).andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("SELECT status FROM activation_codes WHERE code = ?", String.class, code)).isEqualTo("AVAILABLE");
        assertThat(tenants.findBySlug("cafe-dos")).isEmpty();
    }

    @Test
    void dosRegistrosSimultaneosConElMismoCodigoSoloUnoEntra() throws Exception {
        String code = newCode(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int n = i;
            Callable<Integer> task = () -> postJson("/api/v1/auth/register", registerJson("u" + n + "@cafe.test", "cafe-" + n, code))
                    .andReturn().getResponse().getStatus();
            results.add(pool.submit(task));
        }
        int accepted = 0;
        for (Future<Integer> result : results) {
            if (result.get() == 202) {
                accepted++;
            }
        }
        pool.shutdown();

        assertThat(accepted).isEqualTo(1);
        assertThat(count("tenants")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activation_codes WHERE status = 'REDEEMED'", Long.class)).isEqualTo(1);
    }

    @Test
    void elPlanVencidoTieneTresDiasDeGraciaLuegoSePausaYALos90DiasSeArchiva() throws Exception {
        Tenant gracia = publishedOwner("gracia", Instant.now().minus(1, ChronoUnit.DAYS));
        publishedOwner("vencido", Instant.now().minus(5, ChronoUnit.DAYS));
        publishedOwner("archivado", Instant.now().minus(100, ChronoUnit.DAYS));
        publishedOwner("vigente", Instant.now().plus(10, ChronoUnit.DAYS));
        Tenant demo = tenant("demo");
        demo.setPublished(true);
        tenants.save(demo);

        Cookie enGracia = login("owner@gracia.test");
        mvc.perform(get("/api/v1/auth/me").cookie(enGracia)).andExpect(jsonPath("$.plan.status").value("GRACE"))
                .andExpect(jsonPath("$.plan.graceEndsAt").value(org.hamcrest.Matchers.startsWith(gracia.getPlanExpiresAt().plus(3, ChronoUnit.DAYS).toString().substring(0, 19))));
        mvc.perform(withXsrf(post("/api/v1/admin/categories").cookie(enGracia), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sigo editando\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/public/sites/gracia")).andExpect(status().isOk()).andExpect(jsonPath("$.reservationsEnabled").value(true));

        Cookie owner = login("owner@vencido.test");
        mvc.perform(get("/api/v1/admin/site").cookie(owner)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(owner))
                .andExpect(jsonPath("$.plan.status").value("EXPIRED")).andExpect(jsonPath("$.plan.expiresAt").exists());
        mvc.perform(withXsrf(post("/api/v1/admin/categories").cookie(owner), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nueva\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_expired"));
        assertThat(planPut(owner, "/api/v1/admin/site/publication", "{\"published\":false}")).contains("plan_expired");
        assertThat(tenants.findBySlug("vencido").orElseThrow().isPublished()).isTrue();
        mvc.perform(get("/api/v1/public/sites/vencido")).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("site_paused"));

        mvc.perform(get("/api/v1/auth/me").cookie(login("owner@archivado.test"))).andExpect(jsonPath("$.plan.status").value("ARCHIVED"));
        mvc.perform(get("/api/v1/public/sites/archivado")).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/public/sites/vigente")).andExpect(jsonPath("$.reservationsEnabled").value(true));
        mvc.perform(get("/api/v1/public/sites/demo")).andExpect(jsonPath("$.reservationsEnabled").value(true));
        Cookie activo = login("owner@vigente.test");
        mvc.perform(withXsrf(post("/api/v1/admin/categories").cookie(activo), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nueva\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/auth/me").cookie(activo)).andExpect(jsonPath("$.plan.status").value("ACTIVE"));
    }

    @Test
    void renovarDuranteLaGraciaCuentaDesdeElVencimientoOriginalYDespuesDesdeHoy() throws Exception {
        Instant vencio = Instant.now().minus(2, ChronoUnit.DAYS);
        Tenant enGracia = ownerWithPlan("gracia", vencio);
        Tenant pausado = ownerWithPlan("pausado", Instant.now().minus(20, ChronoUnit.DAYS));

        mvc.perform(withXsrf(post("/api/v1/admin/plan/redeem").cookie(login("owner@gracia.test")), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(newCode(1)))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(withXsrf(post("/api/v1/admin/plan/redeem").cookie(login("owner@pausado.test")), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(newCode(1)))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(tenants.findById(enGracia.getId()).orElseThrow().getPlanExpiresAt())
                .isBetween(vencio.plus(27, ChronoUnit.DAYS), vencio.plus(32, ChronoUnit.DAYS));
        assertThat(tenants.findById(pausado.getId()).orElseThrow().getPlanExpiresAt())
                .isBetween(Instant.now().plus(27, ChronoUnit.DAYS), Instant.now().plus(32, ChronoUnit.DAYS));
    }

    @Test
    void elSuperadminFiltraLosNegociosPorEtapaDelPlanConSuWhatsApp() throws Exception {
        Tenant pronto = ownerWithPlan("pronto", Instant.now().plus(3, ChronoUnit.DAYS));
        pronto.setWhatsappNumber("5491100000009");
        tenants.save(pronto);
        ownerWithPlan("gracia", Instant.now().minus(1, ChronoUnit.DAYS));
        ownerWithPlan("pausado", Instant.now().minus(10, ChronoUnit.DAYS));
        ownerWithPlan("archivado", Instant.now().minus(120, ChronoUnit.DAYS));
        ownerWithPlan("tranquilo", Instant.now().plus(40, ChronoUnit.DAYS));

        mvc.perform(get("/api/v1/superadmin/tenants").param("status", "soon").cookie(sa))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].slug").value("pronto"))
                .andExpect(jsonPath("$.content[0].planStatus").value("SOON")).andExpect(jsonPath("$.content[0].whatsappNumber").value("5491100000009"));
        mvc.perform(get("/api/v1/superadmin/tenants").param("status", "grace").cookie(sa))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].planStatus").value("GRACE"));
        mvc.perform(get("/api/v1/superadmin/tenants").param("status", "paused").cookie(sa))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].planStatus").value("EXPIRED"));
        mvc.perform(get("/api/v1/superadmin/tenants").param("status", "archived").cookie(sa))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].planStatus").value("ARCHIVED"));
    }

    private Tenant publishedOwner(String slug, Instant expires) throws Exception {
        Tenant tenant = ownerWithPlan(slug, expires);
        tenant.setPublished(true);
        return tenants.save(tenant);
    }

    @Test
    void renovarConUnCodigoNuevoDevuelveLaEdicionYEliminaElCodigoAnterior() throws Exception {
        Tenant tenant = ownerWithPlan("vencido", Instant.now().minus(2, ChronoUnit.DAYS));
        String viejo = newCode(1);
        jdbc.update("UPDATE activation_codes SET status = 'REDEEMED', tenant_id = ?, redeemed_at = now() WHERE code = ?",
                tenant.getId(), viejo);
        String nuevo = newCode(6);
        Cookie owner = login("owner@vencido.test");

        mvc.perform(withXsrf(post("/api/v1/admin/plan/redeem").cookie(owner), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(nuevo.toLowerCase())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.months").value(6)).andExpect(jsonPath("$.qrCustomization").value(true));
        mvc.perform(get("/api/v1/auth/me").cookie(owner))
                .andExpect(jsonPath("$.plan.months").value(6)).andExpect(jsonPath("$.plan.qrCustomization").value(true));

        assertThat(tenants.findById(tenant.getId()).orElseThrow().getPlanExpiresAt())
                .isBetween(Instant.now().plus(178, ChronoUnit.DAYS), Instant.now().plus(186, ChronoUnit.DAYS));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activation_codes WHERE code = ?", Long.class, viejo)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activation_codes WHERE tenant_id = ?", Long.class, tenant.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM activation_codes WHERE code = ?", String.class, nuevo)).isEqualTo("REDEEMED");
        mvc.perform(withXsrf(post("/api/v1/admin/categories").cookie(owner), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ya puedo\"}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'PLAN_RENEWED'", Long.class)).isEqualTo(1);
    }

    @Test
    void renovarAntesDeTiempoSumaLaDuracionAlVencimientoActual() throws Exception {
        Instant actual = Instant.now().plus(20, ChronoUnit.DAYS);
        Tenant tenant = ownerWithPlan("vigente", actual);
        Cookie owner = login("owner@vigente.test");

        mvc.perform(withXsrf(post("/api/v1/admin/plan/redeem").cookie(owner), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(newCode(1)))).andExpect(status().isOk());

        assertThat(tenants.findById(tenant.getId()).orElseThrow().getPlanExpiresAt())
                .isBetween(actual.plus(27, ChronoUnit.DAYS), actual.plus(32, ChronoUnit.DAYS));
    }

    @Test
    void renovarConUnCodigoInvalidoUsadoOSinPermisoNoCambiaNada() throws Exception {
        Instant vencimiento = Instant.now().minus(1, ChronoUnit.DAYS);
        Tenant tenant = ownerWithPlan("vencido", vencimiento);
        Cookie owner = login("owner@vencido.test");
        Cookie editor = login("editor@vencido.test");
        String usado = newCode(1);
        jdbc.update("UPDATE activation_codes SET status = 'REDEEMED', tenant_id = ? WHERE code = ?", ownerWithPlan("otro", null).getId(), usado);

        for (String code : new String[] { "DLR-AAAA-BBBB", "basura", usado }) {
            mvc.perform(withXsrf(post("/api/v1/admin/plan/redeem").cookie(owner), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"%s\"}".formatted(code)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_code"));
        }
        mvc.perform(withXsrf(post("/api/v1/admin/plan/redeem").cookie(editor), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(newCode(1)))).andExpect(status().isForbidden());

        assertThat(tenants.findById(tenant.getId()).orElseThrow().getPlanExpiresAt().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(vencimiento.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void elSuperadminListaFiltraYGestionaLosCodigos() throws Exception {
        batch(1, 3);
        batch(12, 2);
        String libre = newCode(1);
        Tenant tenant = ownerWithPlan("canje", Instant.now().plus(5, ChronoUnit.DAYS));
        String canjeado = newCode(3);
        jdbc.update("UPDATE activation_codes SET status = 'REDEEMED', tenant_id = ?, redeemed_by_email = 'owner@canje.test', redeemed_at = now() WHERE code = ?",
                tenant.getId(), canjeado);

        mvc.perform(get("/api/v1/superadmin/codes").param("durationMonths", "12").cookie(sa))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/superadmin/codes").param("status", "redeemed").cookie(sa))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].tenantSlug").value("canje"))
                .andExpect(jsonPath("$.content[0].redeemedByEmail").value("owner@canje.test"));
        mvc.perform(get("/api/v1/superadmin/codes").cookie(sa))
                .andExpect(jsonPath("$.content[0].code").value(canjeado))
                .andExpect(jsonPath("$.content[0].status").value("REDEEMED"))
                .andExpect(jsonPath("$.content[0].tenantCreatedAt").exists())
                .andExpect(jsonPath("$.content[0].planExpiresAt").exists())
                .andExpect(jsonPath("$.content[0].planStatus").value("SOON"))
                .andExpect(jsonPath("$.content[1].planStatus").doesNotExist());
        mvc.perform(get("/api/v1/superadmin/codes").param("query", libre.substring(4, 9)).cookie(sa))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get("/api/v1/superadmin/codes/stats").cookie(sa))
                .andExpect(jsonPath("$.redeemed").value(1)).andExpect(jsonPath("$.available").value(6))
                .andExpect(jsonPath("$.plansExpiringSoon").value(1));
        mvc.perform(get("/api/v1/superadmin/codes/batches").cookie(sa)).andExpect(jsonPath("$", hasSize(4)));

        UUID libreId = jdbc.queryForObject("SELECT id FROM activation_codes WHERE code = ?", UUID.class, libre);
        UUID canjeadoId = jdbc.queryForObject("SELECT id FROM activation_codes WHERE code = ?", UUID.class, canjeado);
        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/" + libreId + "/disable").cookie(sa), newXsrf())).andExpect(status().isNoContent());
        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/" + libreId + "/disable").cookie(sa), newXsrf())).andExpect(status().isConflict());
        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/" + libreId + "/enable").cookie(sa), newXsrf())).andExpect(status().isNoContent());
        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/" + canjeadoId + "/enable").cookie(sa), newXsrf())).andExpect(status().isConflict());
        mvc.perform(withXsrf(delete("/api/v1/superadmin/codes/" + libreId).cookie(sa), newXsrf())).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM activation_codes WHERE code = ?", Long.class, libre)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action LIKE 'CODE_%'", Long.class)).isEqualTo(5);
        mvc.perform(get("/api/v1/superadmin/tenants").cookie(sa))
                .andExpect(jsonPath("$.content[0].planStatus").value("SOON"))
                .andExpect(jsonPath("$.content[0].planExpiresAt").exists());
    }

    @Test
    void eliminarUnNegocioBorraSuCodigoCanjeado() throws Exception {
        Tenant tenant = ownerWithPlan("adios", Instant.now().plus(5, ChronoUnit.DAYS));
        String code = newCode(1);
        jdbc.update("UPDATE activation_codes SET status = 'REDEEMED', tenant_id = ? WHERE code = ?", tenant.getId(), code);
        tenant.setSuspendedAt(Instant.now());
        tenants.save(tenant);

        mvc.perform(withXsrf(delete("/api/v1/superadmin/tenants/" + tenant.getId()).cookie(sa), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmSlug\":\"adios\"}")).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM activation_codes WHERE code = ?", Long.class, code)).isZero();
        assertThat(tenants.findBySlug("adios")).isEmpty();
    }

    private UUID redeemedCode(String slug, String code) throws Exception {
        Tenant tenant = ownerWithPlan(slug, Instant.now().plus(5, ChronoUnit.DAYS));
        jdbc.update("UPDATE activation_codes SET status = 'REDEEMED', tenant_id = ?, redeemed_at = now() WHERE code = ?", tenant.getId(), code);
        return jdbc.queryForObject("SELECT id FROM activation_codes WHERE code = ?", UUID.class, code);
    }

    @Test
    void deshabilitarUnCodigoCanjeadoSuspendeElNegocioYHabilitarloLoReactiva() throws Exception {
        String code = newCode(1);
        UUID id = redeemedCode("susp", code);

        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/" + id + "/disable").cookie(sa), newXsrf())).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT status FROM activation_codes WHERE id = ?", String.class, id)).isEqualTo("DISABLED");
        assertThat(tenants.findBySlug("susp").orElseThrow().getSuspendedAt()).isNotNull();

        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/" + id + "/enable").cookie(sa), newXsrf())).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT status FROM activation_codes WHERE id = ?", String.class, id)).isEqualTo("REDEEMED");
        assertThat(tenants.findBySlug("susp").orElseThrow().getSuspendedAt()).isNull();
    }

    @Test
    void eliminarUnCodigoCanjeadoExigeLaDireccionYBorraElNegocioEnCascada() throws Exception {
        String code = newCode(1);
        UUID id = redeemedCode("cascada", code);

        mvc.perform(withXsrf(delete("/api/v1/superadmin/codes/" + id).cookie(sa), newXsrf())).andExpect(status().isBadRequest());
        mvc.perform(withXsrf(delete("/api/v1/superadmin/codes/" + id).cookie(sa), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmSlug\":\"otra\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("slug_mismatch"));
        assertThat(tenants.findBySlug("cascada")).isPresent();

        mvc.perform(withXsrf(delete("/api/v1/superadmin/codes/" + id).cookie(sa), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmSlug\":\"cascada\"}")).andExpect(status().isNoContent());

        assertThat(tenants.findBySlug("cascada")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activation_codes WHERE code = ?", Long.class, code)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'owner@cascada.test'", Long.class)).isZero();
    }

    @Test
    void elLoteLlevaElPlanYElListadoYElResumenLoMuestran() throws Exception {
        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/batches").cookie(sa), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Basico\",\"durationMonths\":1,\"quantity\":2,\"planCode\":\"EMPRENDEDOR\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.planCode").value("EMPRENDEDOR"));

        mvc.perform(get("/api/v1/superadmin/codes").cookie(sa)).andExpect(jsonPath("$.content[0].planCode").value("EMPRENDEDOR"));
        mvc.perform(get("/api/v1/superadmin/codes/batches").cookie(sa)).andExpect(jsonPath("$[0].planCode").value("EMPRENDEDOR"));
        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/batches").cookie(sa), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationMonths\":1,\"quantity\":1}")).andExpect(jsonPath("$.planCode").value("PROFESIONAL"));
        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/batches").cookie(sa), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationMonths\":1,\"quantity\":1,\"planCode\":\"NOEXISTE\"}")).andExpect(status().isBadRequest());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}

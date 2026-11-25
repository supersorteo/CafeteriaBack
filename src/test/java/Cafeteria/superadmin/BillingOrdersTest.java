package Cafeteria.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import Cafeteria.billing.BillingService;
import Cafeteria.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;

@TestPropertySource(properties = { "billing.whatsapp.ar=+54 9 11 0000-0001", "billing.whatsapp.uy=+598 99 000 002" })
class BillingOrdersTest extends IntegrationTestSupport {

    static final String OWNER = "root@plataforma.test";
    static final String OWNER_PASSWORD = "Super-Clave-De-Prueba-1";
    static final String DANILO = "danilo@ejemplo.test";
    static final String TEMP = "Temporal.123";
    static final Pattern CODE = Pattern.compile("es: (\\d{6})");

    @Autowired
    SuperAdminAuthService auth;
    @Autowired
    SuperAdminAccounts accounts;

    @BeforeEach
    void reset() {
        auth.resetLockout();
        auth.usePenalty(0);
        auth.useClock(Clock.systemUTC());
    }

    private Cookie signIn(String email, String password) throws Exception {
        MvcResult started = mvc.perform(withXsrf(post("/api/v1/superadmin/auth/login"), newXsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
        Cookie pending = started.getResponse().getCookie("SA_SESSION");
        Matcher matcher = CODE.matcher(mail.last().body());
        assertThat(matcher.find()).isTrue();
        MvcResult done = mvc.perform(withXsrf(post("/api/v1/superadmin/auth/verify").cookie(pending), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(matcher.group(1)))).andReturn();
        Cookie session = done.getResponse().getCookie("SA_SESSION");
        return session != null ? session : pending;
    }

    private Cookie danilo() throws Exception {
        accounts.create(DANILO, TEMP, SuperAdminAccounts.ADMIN, "AR", false);
        return signIn(DANILO, TEMP);
    }

    private String orderJson(String plan, String region, String email) {
        return "{\"planCode\":\"%s\",\"region\":\"%s\",\"email\":\"%s\",\"name\":\"Cliente\"}".formatted(plan, region, email);
    }

    private MvcResult buy(String plan, String region, String email) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/public/billing/orders"), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content(orderJson(plan, region, email))).andReturn();
    }

    private String orderId(String reference) {
        return jdbc.queryForObject("SELECT id::text FROM orders WHERE reference = ?", String.class, reference);
    }

    private String reference(MvcResult result) throws Exception {
        Matcher matcher = Pattern.compile("\"reference\":\"(PED-[A-Z0-9]+)\"").matcher(result.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @Test
    void lasOpcionesPublicasTraenPlanesPreciosYRegionesConWhatsapp() throws Exception {
        mvc.perform(get("/api/v1/public/billing/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans", hasSize(2)))
                .andExpect(jsonPath("$.plans[0].code", is("EMPRENDEDOR")))
                .andExpect(jsonPath("$.plans[0].usdCents", is(900)))
                .andExpect(jsonPath("$.plans[0].maxMenuItems", is(50)))
                .andExpect(jsonPath("$.plans[1].usdCents", is(1900)))
                .andExpect(jsonPath("$.plans[1].fullBranding", is(true)))
                .andExpect(jsonPath("$.regions", hasSize(2)))
                .andExpect(jsonPath("$.regions[0].code", is("AR")))
                .andExpect(jsonPath("$.regions[0].whatsappNumber", is("5491100000001")))
                .andExpect(jsonPath("$.regions[1].whatsappNumber", is("59899000002")))
                .andExpect(jsonPath("$.regions[0].methods[?(@.code=='WHATSAPP')].available").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.regions[0].methods[?(@.code=='MERCADOPAGO')].available").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.regions[1].methods[?(@.code=='MERCADOPAGO')]").isEmpty())
                .andExpect(jsonPath("$.regions[1].methods[?(@.code=='PAYPAL')].available").value(org.hamcrest.Matchers.contains(false)));
    }

    @Test
    void unMetodoDeCobroOnlineQueTodaviaNoEstaActivoSeRechaza() throws Exception {
        for (String method : new String[] { "MERCADOPAGO", "PAYPAL", "BITCOIN" }) {
            MvcResult result = mvc.perform(withXsrf(post("/api/v1/public/billing/orders"), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"planCode\":\"EMPRENDEDOR\",\"region\":\"AR\",\"email\":\"a@ejemplo.test\",\"method\":\"%s\"}".formatted(method)))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString()).contains("method_unavailable");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(buy("EMPRENDEDOR", "AR", "a@ejemplo.test").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void unPedidoNuevoQuedaPendienteConLaReferenciaYElMensajeDeWhatsapp() throws Exception {
        MvcResult created = buy("PROFESIONAL", "UY", "Cliente@Ejemplo.test");

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String reference = reference(created);
        mvc.perform(get("/api/v1/public/billing/orders/" + reference))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.planName", is("Profesional")))
                .andExpect(jsonPath("$.usdCents", is(1900)))
                .andExpect(jsonPath("$.whatsappNumber", is("59899000002")))
                .andExpect(jsonPath("$.emailHint", is("c***@ejemplo.test")))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString(reference)));
        assertThat(jdbc.queryForObject("SELECT buyer_email FROM orders WHERE reference = ?", String.class, reference))
                .isEqualTo("cliente@ejemplo.test");
        mvc.perform(get("/api/v1/public/billing/orders/PED-NOEXISTE")).andExpect(status().isNotFound());
    }

    @Test
    void rechazaPlanesOPaisesInvalidosYReutilizaElPedidoPendienteDelMismoCorreoYPlan() throws Exception {
        assertThat(buy("INEXISTENTE", "AR", "a@ejemplo.test").getResponse().getStatus()).isEqualTo(400);
        assertThat(buy("PROFESIONAL", "BR", "a@ejemplo.test").getResponse().getStatus()).isEqualTo(400);
        String first = reference(buy("EMPRENDEDOR", "AR", "a@ejemplo.test"));
        String again = reference(buy("EMPRENDEDOR", "AR", "A@ejemplo.test"));
        assertThat(again).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
        String other = reference(buy("PROFESIONAL", "AR", "a@ejemplo.test"));
        assertThat(other).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(2);
    }

    @Test
    void confirmarGeneraUnCodigoDelPlanLoEnviaPorCorreoYSePuedeUsarParaRegistrarse() throws Exception {
        String reference = reference(buy("PROFESIONAL", "UY", "nuevo@ejemplo.test"));
        Cookie owner = signIn(OWNER, OWNER_PASSWORD);

        mvc.perform(withXsrf(post("/api/v1/superadmin/orders/" + orderId(reference) + "/confirm").cookie(owner), newXsrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("PAID")))
                .andExpect(jsonPath("$.confirmedBy", is(OWNER))).andExpect(jsonPath("$.activationCode", startsWith("DLR-")));

        String code = jdbc.queryForObject("SELECT payload FROM email_outbox WHERE type = 'ACTIVATION_CODE' AND recipient = ?",
                String.class, "nuevo@ejemplo.test");
        assertThat(jdbc.queryForObject("SELECT plan_code FROM activation_codes WHERE code = ?", String.class, code))
                .isEqualTo("PROFESIONAL");
        assertThat(jdbc.queryForObject("SELECT duration_months FROM activation_codes WHERE code = ?", Integer.class, code))
                .isEqualTo(1);
        mvc.perform(withXsrf(post("/api/v1/superadmin/orders/" + orderId(reference) + "/confirm").cookie(owner), newXsrf()))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/public/billing/orders/" + reference)).andExpect(jsonPath("$.status", is("PAID")));
    }

    @Test
    void cadaAdministradorSoloGestionaLosPedidosDeSuRegionYElDuenoLosVeSeparados() throws Exception {
        String ar = reference(buy("EMPRENDEDOR", "AR", "ar@ejemplo.test"));
        String uy = reference(buy("EMPRENDEDOR", "UY", "uy@ejemplo.test"));
        Cookie danilo = danilo();
        Cookie owner = signIn(OWNER, OWNER_PASSWORD);

        mvc.perform(get("/api/v1/superadmin/orders").cookie(danilo))
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].reference", is(ar)));
        mvc.perform(get("/api/v1/superadmin/orders").cookie(owner))
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].reference", is(uy)));
        mvc.perform(get("/api/v1/superadmin/orders").param("scope", "others").cookie(owner))
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].reference", is(ar)));

        mvc.perform(withXsrf(post("/api/v1/superadmin/orders/" + orderId(uy) + "/confirm").cookie(danilo), newXsrf()))
                .andExpect(status().isNotFound());
        mvc.perform(withXsrf(post("/api/v1/superadmin/orders/" + orderId(ar) + "/confirm").cookie(danilo), newXsrf()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT b.created_by FROM orders o JOIN activation_codes c ON c.id = o.activation_code_id "
                + "JOIN code_batches b ON b.id = c.batch_id WHERE o.reference = ?", String.class, ar)).isEqualTo(DANILO);
    }

    @Test
    void cancelarUnPedidoPendienteYVencerLosAntiguos() throws Exception {
        String one = reference(buy("EMPRENDEDOR", "UY", "uno@ejemplo.test"));
        String two = reference(buy("EMPRENDEDOR", "UY", "dos@ejemplo.test"));
        Cookie owner = signIn(OWNER, OWNER_PASSWORD);

        mvc.perform(withXsrf(post("/api/v1/superadmin/orders/" + orderId(one) + "/cancel").cookie(owner), newXsrf()))
                .andExpect(status().isNoContent());
        mvc.perform(withXsrf(post("/api/v1/superadmin/orders/" + orderId(one) + "/confirm").cookie(owner), newXsrf()))
                .andExpect(status().isConflict());

        jdbc.update("UPDATE orders SET expires_at = now() - interval '1 day' WHERE reference = ?", two);
        assertThat(billing.expireOld()).isEqualTo(1);
        mvc.perform(get("/api/v1/public/billing/orders/" + two)).andExpect(jsonPath("$.status", is("EXPIRED")));
        mvc.perform(withXsrf(post("/api/v1/superadmin/orders/" + orderId(two) + "/confirm").cookie(owner), newXsrf()))
                .andExpect(status().isOk());
    }

    @Test
    void unOperadorDeSoloLecturaVeLosPedidosPeroNuncaElCodigoDeActivacion() throws Exception {
        String reference = reference(buy("EMPRENDEDOR", "AR", "ar@ejemplo.test"));
        Cookie owner = signIn(OWNER, OWNER_PASSWORD);
        mvc.perform(withXsrf(post("/api/v1/superadmin/orders/" + orderId(reference) + "/confirm").cookie(owner), newXsrf()))
                .andExpect(status().isOk());
        accounts.create(DANILO, TEMP, SuperAdminAccounts.OPERATOR, "AR", false);
        Cookie operator = signIn(DANILO, TEMP);

        mvc.perform(get("/api/v1/superadmin/orders").cookie(operator))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].reference", is(reference)))
                .andExpect(jsonPath("$.content[0].activationCode").doesNotExist());
        mvc.perform(get("/api/v1/superadmin/orders").param("scope", "others").cookie(owner))
                .andExpect(jsonPath("$.content[0].activationCode", startsWith("DLR-")));
    }

    private String register(String email, String slug, String code) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/auth/register"), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"Password-12345\",\"businessName\":\"Negocio\",\"slug\":\"%s\",\"activationCode\":\"%s\"}"
                        .formatted(email, slug, code)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
    }

    private String paidCode(String email) throws Exception {
        String reference = reference(buy("PROFESIONAL", "UY", email));
        Cookie owner = signIn(OWNER, OWNER_PASSWORD);
        mvc.perform(withXsrf(post("/api/v1/superadmin/orders/" + orderId(reference) + "/confirm").cookie(owner), newXsrf()))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT payload FROM email_outbox WHERE type = 'ACTIVATION_CODE' AND recipient = ?", String.class, email);
    }

    @Test
    void registrarseConElCorreoDelPedidoNoPideConfirmarElCorreoOtraVez() throws Exception {
        String code = paidCode("cliente@ejemplo.test");

        String body = register("Cliente@Ejemplo.test", "mi-negocio", code);

        assertThat(body).contains("\"verified\":true");
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE email = 'cliente@ejemplo.test'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE type = 'VERIFY_EMAIL'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT plan_code FROM tenants WHERE slug = 'mi-negocio'", String.class)).isEqualTo("PROFESIONAL");
    }

    @Test
    void conOtroCorreoSigueExigiendoConfirmarlo() throws Exception {
        String code = paidCode("cliente@ejemplo.test");

        String body = register("otro@ejemplo.test", "otro-negocio", code);

        assertThat(body).contains("\"verified\":false");
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM users WHERE email = 'otro@ejemplo.test'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE type = 'VERIFY_EMAIL'", Integer.class)).isEqualTo(1);
    }

    @Test
    void elCorreoDelCodigoTraeElEnlaceConElCodigoYElCorreoYaEscritos() throws Exception {
        String code = paidCode("cliente+tienda@ejemplo.test");
        Cafeteria.mail.EmailMessage message = new Cafeteria.mail.EmailTemplates("https://app.ejemplo.test")
                .build(Cafeteria.mail.EmailTemplates.ACTIVATION_CODE, "cliente+tienda@ejemplo.test", code);

        assertThat(message.body()).contains("https://app.ejemplo.test/auth/register?code=" + code + "&email=cliente%2Btienda%40ejemplo.test");
    }

    @Autowired
    BillingService billing;

    @Test
    void soloElDuenoCambiaLosPreciosYSeVenEnLasOpciones() throws Exception {
        Cookie danilo = danilo();
        Cookie owner = signIn(OWNER, OWNER_PASSWORD);
        String body = "{\"planCode\":\"PROFESIONAL\",\"months\":1,\"usdCents\":2500}";

        mvc.perform(withXsrf(put("/api/v1/superadmin/billing/prices").cookie(danilo), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(withXsrf(put("/api/v1/superadmin/billing/prices").cookie(owner), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/public/billing/options")).andExpect(jsonPath("$.plans[1].usdCents", is(2500)));
        mvc.perform(withXsrf(put("/api/v1/superadmin/billing/prices").cookie(owner), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"PROFESIONAL\",\"months\":1,\"usdCents\":5}"))
                .andExpect(status().isBadRequest());
    }
}

package Cafeteria.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import Cafeteria.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;

class OnlinePaymentsTest extends IntegrationTestSupport {

    static final FakeProviders FAKE = FakeProviders.INSTANCE;
    static final String WEBHOOK_SECRET = "webhook-secret-de-prueba";

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("paypal.base-url", FAKE::url);
        registry.add("paypal.client-id", () -> "client-id-publico");
        registry.add("paypal.client-secret", () -> "client-secret");
        registry.add("mercadopago.base-url", FAKE::url);
        registry.add("mercadopago.access-token", () -> "mp-access-token");
        registry.add("mercadopago.public-key", () -> "mp-public-key");
        registry.add("mercadopago.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("billing.whatsapp.ar", () -> "5491100000001");
        registry.add("billing.whatsapp.uy", () -> "59899000002");
    }

    @Autowired
    FxRates fx;
    @Autowired
    OnlineOrderService online;

    @BeforeEach
    void setUp() {
        FAKE.reset();
        fx.record(new BigDecimal("1500"), "MANUAL", "test");
    }

    private String body(String plan, String region, String method, String email, String slug, String proof) {
        return "{\"planCode\":\"%s\",\"region\":\"%s\",\"method\":\"%s\",\"email\":\"%s\",\"businessName\":\"Café %s\",\"slug\":\"%s\",\"password\":\"Clave-Segura-123\",\"emailProof\":\"%s\"}"
                .formatted(plan, region, method, email, slug, slug, proof);
    }

    private MvcResult sendCode(String email) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/public/billing/email/code"), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email))).andReturn();
    }

    private MvcResult verifyCode(String email, String code) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/public/billing/email/verify"), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code))).andReturn();
    }

    private String lastCode(String email) {
        return jdbc.queryForObject("SELECT payload FROM email_outbox WHERE type = 'EMAIL_CODE' AND recipient = ? ORDER BY created_at DESC LIMIT 1",
                String.class, email.toLowerCase());
    }

    private String proofFor(String email) throws Exception {
        if (sendCode(email).getResponse().getStatus() != 204) {
            return "sin-prueba";
        }
        return field(verifyCode(email, lastCode(email)), "proof");
    }

    private MvcResult checkout(String plan, String region, String method, String email, String slug) throws Exception {
        return checkoutWith(plan, region, method, email, slug, proofFor(email));
    }

    private MvcResult checkoutWith(String plan, String region, String method, String email, String slug, String proof) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/public/billing/checkout"), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(plan, region, method, email, slug, proof))).andReturn();
    }

    private static String field(MvcResult result, String name) throws Exception {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(result.getResponse().getContentAsString());
        assertThat(matcher.find()).as(name).isTrue();
        return matcher.group(1);
    }

    private MvcResult capture(String reference) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/public/billing/orders/" + reference + "/paypal/capture"), newXsrf())).andReturn();
    }

    private MvcResult verify(String reference, String paymentId) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/public/billing/orders/" + reference + "/mercadopago/verify"), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"" + paymentId + "\"}")).andReturn();
    }

    private MvcResult session(String reference, String claim) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/public/billing/orders/" + reference + "/session"), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"claimToken\":\"" + claim + "\"}")).andReturn();
    }

    private String orderStatus(String reference) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE reference = ?", String.class, reference);
    }

    private long tenantsWithSlug(String slug) {
        return jdbc.queryForObject("SELECT count(*) FROM tenants WHERE slug = ?", Long.class, slug);
    }

    private void approvePayPal(MvcResult created, String reference, String value, String payerEmail) throws Exception {
        FAKE.paypalCaptureByOrder.put(field(created, "providerRef"), FakeProviders.capture(field(created, "providerRef"), reference, value, payerEmail));
    }

    @Test
    void lasOpcionesTraenLosMetodosOnlineLasClavesPublicasYElPrecioEnPesos() throws Exception {
        mvc.perform(get("/api/v1/public/billing/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkout.paypalClientId", is("client-id-publico")))
                .andExpect(jsonPath("$.checkout.mercadopagoPublicKey", is("mp-public-key")))
                .andExpect(jsonPath("$.plans[0].arsPrice", is(13500)))
                .andExpect(jsonPath("$.plans[1].arsPrice", is(28500)))
                .andExpect(jsonPath("$.regions[0].methods[?(@.code=='PAYPAL')].available").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.regions[0].methods[?(@.code=='MERCADOPAGO')].available").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.regions[1].methods[?(@.code=='MERCADOPAGO')]").isEmpty());
    }

    @Test
    void sinCotizacionMercadoPagoNoEstaDisponible() throws Exception {
        jdbc.update("DELETE FROM fx_rates");
        mvc.perform(get("/api/v1/public/billing/options"))
                .andExpect(jsonPath("$.regions[0].methods[?(@.code=='MERCADOPAGO')].available").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.plans[0].arsPrice").doesNotExist());
        assertThat(checkout("EMPRENDEDOR", "AR", "MERCADOPAGO", "a@ejemplo.test", "sin-tasa").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void elPedidoDePayPalGuardaLosDatosConLaContrasenaCifradaYCreaLaOrdenEnDolares() throws Exception {
        MvcResult created = checkout("PROFESIONAL", "UY", "PAYPAL", "Dueno@Ejemplo.test", "mi-bar");

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String reference = field(created, "reference");
        assertThat(field(created, "providerRef")).startsWith("PP-");
        assertThat(field(created, "claimToken")).hasSizeGreaterThan(20);
        assertThat(FAKE.paypalCreateBodies.get(0)).contains("\"value\":\"19.00\"").contains("\"currency_code\":\"USD\"").contains(reference);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM orders WHERE reference = ?", String.class, reference))
                .startsWith("{bcrypt}").doesNotContain("Clave-Segura-123");
        assertThat(jdbc.queryForObject("SELECT buyer_email FROM orders WHERE reference = ?", String.class, reference)).isEqualTo("dueno@ejemplo.test");
        assertThat(orderStatus(reference)).isEqualTo("PENDING");
    }

    @Test
    void rechazaDatosInvalidosCorreosYDireccionesYaUsadosYMetodosNoOnline() throws Exception {
        assertThat(checkout("PROFESIONAL", "UY", "WHATSAPP", "a1@ejemplo.test", "uno-ok").getResponse().getStatus()).isEqualTo(409);
        assertThat(checkout("INEXISTENTE", "UY", "PAYPAL", "a2@ejemplo.test", "dos-ok").getResponse().getStatus()).isEqualTo(400);
        assertThat(checkout("PROFESIONAL", "UY", "MERCADOPAGO", "a3@ejemplo.test", "tres-ok").getResponse().getStatus()).isEqualTo(409);
        assertThat(checkout("PROFESIONAL", "UY", "PAYPAL", "no-es-correo", "cuatro-ok").getResponse().getStatus()).isEqualTo(400);
        assertThat(checkout("PROFESIONAL", "UY", "PAYPAL", "a4@ejemplo.test", "admin").getResponse().getStatus()).isEqualTo(400);
        MvcResult short1 = mvc.perform(withXsrf(post("/api/v1/public/billing/checkout"), newXsrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body("PROFESIONAL", "UY", "PAYPAL", "a9@ejemplo.test", "cinco-ok", proofFor("a9@ejemplo.test")).replace("Clave-Segura-123", "corta"))).andReturn();
        assertThat(short1.getResponse().getStatus()).isEqualTo(400);

        assertThat(checkout("PROFESIONAL", "UY", "PAYPAL", "a5@ejemplo.test", "mi-bar").getResponse().getStatus()).isEqualTo(201);
        MvcResult sameSlug = checkout("PROFESIONAL", "UY", "PAYPAL", "otro@ejemplo.test", "mi-bar");
        assertThat(sameSlug.getResponse().getStatus()).isEqualTo(409);
        assertThat(sameSlug.getResponse().getContentAsString()).contains("slug_taken");

        member("existente@ejemplo.test", tenant("ya-existe"), Cafeteria.tenant.Role.OWNER, true);
        MvcResult sameEmail = checkout("PROFESIONAL", "UY", "PAYPAL", "existente@ejemplo.test", "otro-bar");
        assertThat(sameEmail.getResponse().getStatus()).isEqualTo(409);
        assertThat(sameEmail.getResponse().getContentAsString()).contains("email_in_use");
        assertThat(checkout("PROFESIONAL", "UY", "PAYPAL", "nuevo@ejemplo.test", "ya-existe").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void alCapturarElPagoDePayPalSeCreaElNegocioConSuPlanYSeInicaSesionUnaSolaVez() throws Exception {
        MvcResult created = checkout("PROFESIONAL", "AR", "PAYPAL", "dueno@ejemplo.test", "bar-pago");
        String reference = field(created, "reference");
        approvePayPal(created, reference, "19.00", "dueno@ejemplo.test");

        MvcResult paid = capture(reference);

        assertThat(paid.getResponse().getContentAsString()).contains("\"status\":\"PAID\"").contains("\"ready\":true");
        assertThat(tenantsWithSlug("bar-pago")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT plan_code FROM tenants WHERE slug = 'bar-pago'", String.class)).isEqualTo("PROFESIONAL");
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE email = 'dueno@ejemplo.test'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM orders WHERE reference = ?", String.class, reference)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE type = 'PAYMENT_RECEIPT' AND recipient = 'dueno@ejemplo.test'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE type = 'VERIFY_EMAIL'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT b.label FROM orders o JOIN activation_codes c ON c.id = o.activation_code_id JOIN code_batches b ON b.id = c.batch_id WHERE o.reference = ?",
                String.class, reference)).contains(reference);

        MvcResult logged = session(reference, field(created, "claimToken"));
        assertThat(logged.getResponse().getStatus()).isEqualTo(200);
        Cookie[] cookies = logged.getResponse().getCookies();
        mvc.perform(get("/api/v1/auth/me").cookie(cookies)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("dueno@ejemplo.test"))).andExpect(jsonPath("$.plan.planCode", is("PROFESIONAL")));
        assertThat(session(reference, field(created, "claimToken")).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void capturarDosVecesNoCreaDosNegociosNiDosCuentas() throws Exception {
        MvcResult created = checkout("EMPRENDEDOR", "UY", "PAYPAL", "uno@ejemplo.test", "bar-uno");
        String reference = field(created, "reference");
        approvePayPal(created, reference, "9.00", "uno@ejemplo.test");

        capture(reference);
        capture(reference);

        assertThat(tenantsWithSlug("bar-uno")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'uno@ejemplo.test'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_events WHERE provider = 'PAYPAL'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT plan_code FROM tenants WHERE slug = 'bar-uno'", String.class)).isEqualTo("EMPRENDEDOR");
    }

    @Test
    void elCorreoQueInformaElProveedorNoVerificaNadaSoloElCodigoEnviadoAlCorreo() throws Exception {
        MvcResult created = checkout("PROFESIONAL", "UY", "PAYPAL", "formulario@ejemplo.test", "bar-dos");
        String reference = field(created, "reference");
        approvePayPal(created, reference, "19.00", "otra-persona@paypal.test");

        capture(reference);

        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE email = 'formulario@ejemplo.test'", Boolean.class)).isTrue();
        assertThat(session(reference, field(created, "claimToken")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void elCheckoutExigeUnaPruebaDeCorreoValidaDeUnSoloUsoYDeEseMismoCorreo() throws Exception {
        assertThat(checkoutWith("PROFESIONAL", "UY", "PAYPAL", "a@ejemplo.test", "sin-prueba", "inventada").getResponse().getStatus()).isEqualTo(400);

        String proof = proofFor("a@ejemplo.test");
        assertThat(checkoutWith("PROFESIONAL", "UY", "PAYPAL", "b@ejemplo.test", "otra-prueba", proof).getResponse().getContentAsString())
                .contains("email_not_verified");
        assertThat(checkoutWith("PROFESIONAL", "UY", "PAYPAL", "a@ejemplo.test", "uso-uno", proof).getResponse().getStatus()).isEqualTo(201);
        assertThat(checkoutWith("PROFESIONAL", "UY", "PAYPAL", "a@ejemplo.test", "uso-dos", proof).getResponse().getContentAsString())
                .contains("email_not_verified");
    }

    @Test
    void siLaDireccionYaEstaOcupadaLaPruebaDeCorreoNoSeGastaYSePuedeReintentar() throws Exception {
        tenant("ocupada");
        String proof = proofFor("c@ejemplo.test");

        assertThat(checkoutWith("PROFESIONAL", "UY", "PAYPAL", "c@ejemplo.test", "ocupada", proof).getResponse().getStatus()).isEqualTo(409);
        assertThat(checkoutWith("PROFESIONAL", "UY", "PAYPAL", "c@ejemplo.test", "libre-ya", proof).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void elCodigoDeCorreoSeLimitaPorIntentosPorEnviosYNoSeEnviaACorreosYaRegistrados() throws Exception {
        assertThat(sendCode("d@ejemplo.test").getResponse().getStatus()).isEqualTo(204);
        String code = lastCode("d@ejemplo.test");
        assertThat(code).matches("\\d{6}");
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThat(verifyCode("d@ejemplo.test", wrong).getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(verifyCode("d@ejemplo.test", code).getResponse().getStatus()).isEqualTo(400);

        for (int i = 0; i < 4; i++) {
            assertThat(sendCode("d@ejemplo.test").getResponse().getStatus()).isEqualTo(204);
        }
        assertThat(sendCode("d@ejemplo.test").getResponse().getStatus()).isEqualTo(429);

        member("registrado@ejemplo.test", tenant("ya-registrado"), Cafeteria.tenant.Role.OWNER, true);
        assertThat(sendCode("registrado@ejemplo.test").getResponse().getStatus()).isEqualTo(409);
        assertThat(sendCode("no-es-correo").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void unPagoConMontoOReferenciaQueNoCoincidenNoCreaNada() throws Exception {
        MvcResult created = checkout("PROFESIONAL", "UY", "PAYPAL", "malo@ejemplo.test", "bar-malo");
        String reference = field(created, "reference");
        approvePayPal(created, reference, "1.00", "malo@ejemplo.test");

        MvcResult result = capture(reference);

        assertThat(result.getResponse().getContentAsString()).contains("\"ready\":false");
        assertThat(orderStatus(reference)).isEqualTo("FAILED");
        assertThat(tenantsWithSlug("bar-malo")).isZero();
        assertThat(session(reference, field(created, "claimToken")).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void capturarSinQueElClienteHayaAprobadoNoCreaNadaYElPedidoSigueAbierto() throws Exception {
        MvcResult created = checkout("PROFESIONAL", "UY", "PAYPAL", "pendiente@ejemplo.test", "bar-pend");
        String reference = field(created, "reference");

        assertThat(capture(reference).getResponse().getStatus()).isEqualTo(502);
        assertThat(orderStatus(reference)).isEqualTo("PENDING");
        assertThat(tenantsWithSlug("bar-pend")).isZero();
    }

    @Test
    void siLaDireccionSeOcupaDespuesDePagarElPagoQuedaRegistradoYNoSePierde() throws Exception {
        MvcResult created = checkout("PROFESIONAL", "UY", "PAYPAL", "tarde@ejemplo.test", "bar-tarde");
        String reference = field(created, "reference");
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE reference = ?", reference);
        tenant("bar-tarde");
        jdbc.update("UPDATE orders SET status = 'PENDING' WHERE reference = ?", reference);
        approvePayPal(created, reference, "19.00", "tarde@ejemplo.test");

        MvcResult result = capture(reference);

        assertThat(result.getResponse().getContentAsString()).contains("\"status\":\"PAID\"").contains("\"ready\":false");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM orders WHERE reference = ?", String.class, reference)).contains("no se pudo crear la cuenta");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'tarde@ejemplo.test'", Integer.class)).isZero();
    }

    @Test
    void mercadoPagoCreaLaPreferenciaEnPesosConLaReferenciaYSeVerificaAlVolver() throws Exception {
        MvcResult created = checkout("PROFESIONAL", "AR", "MERCADOPAGO", "arg@ejemplo.test", "bar-ar");
        String reference = field(created, "reference");

        assertThat(field(created, "initPoint")).startsWith("https://mp.test/checkout");
        String preference = FAKE.preferenceBodies.get(0);
        assertThat(preference).contains("\"external_reference\":\"" + reference + "\"").contains("\"currency_id\":\"ARS\"").contains("28500")
                .contains("/pago/resultado?ref=" + reference);
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM orders WHERE reference = ?", Integer.class, reference)).isEqualTo(2850000);

        FAKE.mpPayments.put("777", FakeProviders.mpPayment("777", "approved", reference, "28500.0", "ARS", "comprador@test.mp"));
        MvcResult paid = verify(reference, "777");

        assertThat(paid.getResponse().getContentAsString()).contains("\"status\":\"PAID\"").contains("\"ready\":true");
        assertThat(jdbc.queryForObject("SELECT plan_code FROM tenants WHERE slug = 'bar-ar'", String.class)).isEqualTo("PROFESIONAL");
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE email = 'arg@ejemplo.test'", Boolean.class)).isTrue();
        assertThat(session(reference, field(created, "claimToken")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void mercadoPagoRechazaPagosDeOtroPedidoMontosIncorrectosYMantienePendienteLoRechazado() throws Exception {
        MvcResult created = checkout("PROFESIONAL", "AR", "MERCADOPAGO", "arg@ejemplo.test", "bar-ar2");
        String reference = field(created, "reference");

        FAKE.mpPayments.put("1", FakeProviders.mpPayment("1", "approved", "PED-OTRO", "28500.0", "ARS", "x@test.mp"));
        verify(reference, "1");
        assertThat(orderStatus(reference)).isEqualTo("PENDING");

        FAKE.mpPayments.put("2", FakeProviders.mpPayment("2", "rejected", reference, "28500.0", "ARS", "x@test.mp"));
        verify(reference, "2");
        assertThat(orderStatus(reference)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM orders WHERE reference = ?", String.class, reference)).contains("rejected");

        FAKE.mpPayments.put("3", FakeProviders.mpPayment("3", "approved", reference, "100.0", "ARS", "x@test.mp"));
        verify(reference, "3");
        assertThat(orderStatus(reference)).isEqualTo("FAILED");
        assertThat(tenantsWithSlug("bar-ar2")).isZero();
    }

    private MvcResult webhook(String dataId, String requestId, String signature) throws Exception {
        return mvc.perform(post("/api/v1/public/billing/webhooks/mercadopago?data.id=" + dataId + "&type=payment")
                .header("x-request-id", requestId).header("x-signature", signature)).andReturn();
    }

    private String sign(String dataId, String requestId, long ts) {
        return "ts=" + ts + ",v1=" + MercadoPagoClient.hmac(WEBHOOK_SECRET, "id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";");
    }

    @Test
    void elWebhookDeMercadoPagoSoloAceptaFirmasValidasRecientesYEsIdempotente() throws Exception {
        MvcResult created = checkout("EMPRENDEDOR", "AR", "MERCADOPAGO", "hook@ejemplo.test", "bar-hook");
        String reference = field(created, "reference");
        FAKE.mpPayments.put("900", FakeProviders.mpPayment("900", "approved", reference, "13500.0", "ARS", "hook@ejemplo.test"));
        long now = Instant.now().getEpochSecond();

        assertThat(webhook("900", "req-1", "ts=" + now + ",v1=" + "0".repeat(64)).getResponse().getStatus()).isEqualTo(401);
        assertThat(webhook("900", "req-1", sign("900", "req-1", now - 3600)).getResponse().getStatus()).isEqualTo(401);
        assertThat(webhook("900", "req-1", "basura").getResponse().getStatus()).isEqualTo(401);
        assertThat(orderStatus(reference)).isEqualTo("PENDING");

        assertThat(webhook("900", "req-1", sign("900", "req-1", now)).getResponse().getStatus()).isEqualTo(200);
        assertThat(orderStatus(reference)).isEqualTo("PAID");
        assertThat(tenantsWithSlug("bar-hook")).isEqualTo(1);

        assertThat(webhook("900", "req-1", sign("900", "req-1", now)).getResponse().getStatus()).isEqualTo(200);
        assertThat(webhook("900", "req-2", sign("900", "req-2", now)).getResponse().getStatus()).isEqualTo(200);
        assertThat(tenantsWithSlug("bar-hook")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'hook@ejemplo.test'", Integer.class)).isEqualTo(1);
    }

    @Test
    void laReconciliacionConfirmaLosPagosQueNuncaAvisaronYExpiraLosAbandonados() throws Exception {
        MvcResult created = checkout("EMPRENDEDOR", "AR", "MERCADOPAGO", "rec@ejemplo.test", "bar-rec");
        String reference = field(created, "reference");
        FAKE.mpSearch.put(reference, List.of(FakeProviders.mpPayment("55", "approved", reference, "13500.0", "ARS", "rec@ejemplo.test")));
        MvcResult abandoned = checkout("EMPRENDEDOR", "UY", "PAYPAL", "aband@ejemplo.test", "bar-aband");
        jdbc.update("UPDATE orders SET expires_at = now() - interval '3 hours' WHERE reference = ?", field(abandoned, "reference"));

        online.reconcile();

        assertThat(orderStatus(reference)).isEqualTo("PAID");
        assertThat(tenantsWithSlug("bar-rec")).isEqualTo(1);
        assertThat(orderStatus(field(abandoned, "reference"))).isEqualTo("EXPIRED");
        assertThat(tenantsWithSlug("bar-aband")).isZero();
    }

    @Test
    void elNegocioQuedaATombreDelAdministradorDeLaRegion() throws Exception {
        accounts.create("danilo@ejemplo.test", "Temporal.123", Cafeteria.superadmin.SuperAdminAccounts.ADMIN, "AR", false);
        MvcResult created = checkout("EMPRENDEDOR", "AR", "PAYPAL", "region@ejemplo.test", "bar-region");
        String reference = field(created, "reference");
        approvePayPal(created, reference, "9.00", "region@ejemplo.test");

        capture(reference);

        assertThat(jdbc.queryForObject("SELECT b.created_by FROM orders o JOIN activation_codes c ON c.id = o.activation_code_id JOIN code_batches b ON b.id = c.batch_id WHERE o.reference = ?",
                String.class, reference)).isEqualTo("danilo@ejemplo.test");
    }

    @Autowired
    Cafeteria.superadmin.SuperAdminAccounts accounts;
}

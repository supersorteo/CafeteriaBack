package Cafeteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;

class RegistrationFlowTest extends IntegrationTestSupport {

    static final String NEW_PASSWORD = "Password-12345";

    private String registerJson(String email, String slug) {
        return "{\"email\":\"%s\",\"password\":\"%s\",\"businessName\":\"Café %s\",\"slug\":\"%s\"}"
                .formatted(email, NEW_PASSWORD, slug, slug);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void register(String email, String slug) throws Exception {
        postJson("/api/v1/auth/register", registerJson(email, slug)).andExpect(status().isAccepted());
    }

    @Test
    void registroCreaCuentaNegocioYOwnerPendientesDeVerificar() throws Exception {
        register("Nuevo@Cafe.test", "cafe-nuevo");

        assertThat(count("users")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT email FROM users", String.class)).isEqualTo("nuevo@cafe.test");
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM users", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT published FROM tenants WHERE slug = 'cafe-nuevo'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT role FROM tenant_users", String.class)).isEqualTo(Role.OWNER.name());
        assertThat(count("account_tokens")).isEqualTo(1);
        assertThat(count("email_outbox")).isEqualTo(1);
        assertThat(mail.sent).isEmpty();
    }

    @Test
    void cuentaSinVerificarNoPuedeIniciarSesion() throws Exception {
        register("nuevo@cafe.test", "cafe-nuevo");

        mvc.perform(loginRequest("nuevo@cafe.test", NEW_PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    void outboxEnviaElCorreoYRedactaElPayload() throws Exception {
        register("nuevo@cafe.test", "cafe-nuevo");

        assertThat(outboxProcessor.processBatch()).isEqualTo(1);

        assertThat(mail.sent).hasSize(1);
        assertThat(mail.last().to()).isEqualTo("nuevo@cafe.test");
        assertThat(mail.last().body()).contains("/auth/verify-email?token=");
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox", String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT payload IS NULL FROM email_outbox", Boolean.class)).isTrue();
    }

    @Test
    void elTokenNoSeGuardaEnClaroEnLaTablaDeTokens() throws Exception {
        register("nuevo@cafe.test", "cafe-nuevo");
        outboxProcessor.processBatch();

        String raw = mail.lastToken();
        String stored = jdbc.queryForObject("SELECT token_hash FROM account_tokens", String.class);

        assertThat(stored).isNotEqualTo(raw).hasSize(64);
    }

    @Test
    void verificarCorreoActivaLaCuentaYElTokenEsDeUnSoloUso() throws Exception {
        register("nuevo@cafe.test", "cafe-nuevo");
        outboxProcessor.processBatch();
        String token = mail.lastToken();

        postJson("/api/v1/auth/verify-email", "{\"token\":\"%s\"}".formatted(token)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users", Boolean.class)).isTrue();
        login("nuevo@cafe.test", NEW_PASSWORD);

        postJson("/api/v1/auth/verify-email", "{\"token\":\"%s\"}".formatted(token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_token"));
    }

    @Test
    void tokenCaducadoOInventadoSeRechazaSinCambiarLaCuenta() throws Exception {
        register("nuevo@cafe.test", "cafe-nuevo");
        outboxProcessor.processBatch();
        String token = mail.lastToken();
        jdbc.update("UPDATE account_tokens SET expires_at = now() - interval '1 minute'");

        postJson("/api/v1/auth/verify-email", "{\"token\":\"%s\"}".formatted(token)).andExpect(status().isBadRequest());
        postJson("/api/v1/auth/verify-email", "{\"token\":\"inventado\"}").andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM users", Boolean.class)).isTrue();
    }

    @Test
    void emailYaVerificadoDevuelveLaMismaRespuestaSinCrearNada() throws Exception {
        member("existe@cafe.test", tenant("cafe-uno"), Role.OWNER, true);
        long usersBefore = count("users");
        long tenantsBefore = count("tenants");

        postJson("/api/v1/auth/register", registerJson("existe@cafe.test", "cafe-dos"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Revisa tu correo para confirmar la cuenta"));

        assertThat(count("users")).isEqualTo(usersBefore);
        assertThat(count("tenants")).isEqualTo(tenantsBefore);
        outboxProcessor.processBatch();
        assertThat(mail.last().subject()).isEqualTo("Ya tienes una cuenta");
        assertThat(mail.last().body()).doesNotContain("token=");
    }

    @Test
    void reintentoTrasRespuestaPerdidaNoDuplicaYRespetaElEnfriamiento() throws Exception {
        register("nuevo@cafe.test", "cafe-nuevo");
        register("nuevo@cafe.test", "cafe-nuevo");

        assertThat(count("users")).isEqualTo(1);
        assertThat(count("tenants")).isEqualTo(1);
        assertThat(count("account_tokens")).isEqualTo(1);

        outboxProcessor.processBatch();
        String viejo = mail.lastToken();
        jdbc.update("UPDATE account_tokens SET created_at = now() - interval '5 minutes'");
        register("nuevo@cafe.test", "cafe-nuevo");
        outboxProcessor.processBatch();

        assertThat(count("users")).isEqualTo(1);
        assertThat(mail.lastToken()).isNotEqualTo(viejo);
        postJson("/api/v1/auth/verify-email", "{\"token\":\"%s\"}".formatted(viejo)).andExpect(status().isBadRequest());
        postJson("/api/v1/auth/verify-email", "{\"token\":\"%s\"}".formatted(mail.lastToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void slugOcupadoDevuelve409SinDejarDatosParciales() throws Exception {
        tenant("cafe-nuevo");
        long usersBefore = count("users");

        postJson("/api/v1/auth/register", registerJson("otro@cafe.test", "cafe-nuevo"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("slug_taken"));

        assertThat(count("users")).isEqualTo(usersBefore);
        assertThat(count("tenant_users")).isZero();
        assertThat(count("account_tokens")).isZero();
        assertThat(count("email_outbox")).isZero();
    }

    @Test
    void validacionesDeRegistro() throws Exception {
        for (String slug : new String[] { "ab", "-malo", "doble--guion", "Mayus", "con espacio" }) {
            postJson("/api/v1/auth/register", registerJson("a@cafe.test", slug)).andExpect(status().isBadRequest());
        }
        postJson("/api/v1/auth/register", registerJson("a@cafe.test", "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("slug_reserved"));
        postJson("/api/v1/auth/register",
                "{\"email\":\"a@cafe.test\",\"password\":\"corta\",\"businessName\":\"X\",\"slug\":\"cafe-ok\"}")
                .andExpect(status().isBadRequest());
        postJson("/api/v1/auth/register",
                "{\"email\":\"no-es-email\",\"password\":\"Password-12345\",\"businessName\":\"X\",\"slug\":\"cafe-ok\"}")
                .andExpect(status().isBadRequest());

        assertThat(count("users")).isZero();
        assertThat(count("tenants")).isZero();
    }

    @Test
    void dosRegistrosSimultaneosConElMismoEmailYSlugDejanUnaSolaCuenta() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                tasks.add(() -> postJson("/api/v1/auth/register", registerJson("carrera@cafe.test", "cafe-carrera"))
                        .andReturn().getResponse().getStatus());
            }
            for (Future<Integer> result : pool.invokeAll(tasks)) {
                assertThat(result.get()).isIn(202, 409);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(count("users")).isEqualTo(1);
        assertThat(count("tenants")).isEqualTo(1);
        assertThat(count("tenant_users")).isEqualTo(1);
        assertThat(count("account_tokens")).isEqualTo(1);
    }

    @Test
    void siSmtpFallaLaCuentaQuedaConsistenteYElEnvioSeReintenta() throws Exception {
        register("nuevo@cafe.test", "cafe-nuevo");
        mail.failing = true;

        assertThat(outboxProcessor.processBatch()).isZero();

        assertThat(count("users")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_outbox", Integer.class)).isEqualTo(1);
        assertThat(outboxProcessor.processBatch()).isZero();
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_outbox", Integer.class)).isEqualTo(1);

        mail.failing = false;
        jdbc.update("UPDATE email_outbox SET next_attempt_at = now()");
        assertThat(outboxProcessor.processBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox", String.class)).isEqualTo("SENT");
    }

    @Test
    void trasAgotarLosIntentosElCorreoPasaAFailedYSeRedactaElPayload() throws Exception {
        register("nuevo@cafe.test", "cafe-nuevo");
        mail.failing = true;

        for (int i = 0; i < 8; i++) {
            jdbc.update("UPDATE email_outbox SET next_attempt_at = now() WHERE status = 'PENDING'");
            outboxProcessor.processBatch();
        }

        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT payload IS NULL FROM email_outbox", Boolean.class)).isTrue();
    }

    @Test
    void reenviarVerificacionNoRevelaSiLaCuentaExiste() throws Exception {
        register("nuevo@cafe.test", "cafe-nuevo");
        outboxProcessor.processBatch();
        jdbc.update("UPDATE account_tokens SET created_at = now() - interval '5 minutes'");
        mail.reset();

        postJson("/api/v1/auth/resend-verification", "{\"email\":\"nadie@cafe.test\"}").andExpect(status().isAccepted());
        postJson("/api/v1/auth/resend-verification", "{\"email\":\"nuevo@cafe.test\"}").andExpect(status().isAccepted());
        outboxProcessor.processBatch();

        assertThat(mail.sent).hasSize(1);
        assertThat(mail.last().to()).isEqualTo("nuevo@cafe.test");
    }

    private Object login(String email, String password) throws Exception {
        mvc.perform(loginRequest(email, password)).andExpect(status().isOk());
        return null;
    }
}

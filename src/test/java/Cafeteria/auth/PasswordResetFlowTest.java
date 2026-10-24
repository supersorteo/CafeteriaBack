package Cafeteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import jakarta.servlet.http.Cookie;

class PasswordResetFlowTest extends IntegrationTestSupport {

    static final String NEW_PASSWORD = "Otra-Clave-98765";

    @BeforeEach
    void usuario() {
        member("owner@a.test", tenant("negocio-a"), Role.OWNER, true);
    }

    private String requestReset() throws Exception {
        postJson("/api/v1/auth/forgot-password", "{\"email\":\"owner@a.test\"}").andExpect(status().isAccepted());
        outboxProcessor.processBatch();
        return mail.lastToken();
    }

    private void reset(String token, String password, int expected) throws Exception {
        postJson("/api/v1/auth/reset-password", "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, password))
                .andExpect(status().is(expected));
    }

    @Test
    void cuentaInexistenteRecibeLaMismaRespuestaYNoSeEnviaNada() throws Exception {
        postJson("/api/v1/auth/forgot-password", "{\"email\":\"nadie@a.test\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Si la cuenta existe, recibirás un correo con instrucciones"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox", Long.class)).isZero();
    }

    @Test
    void resetCambiaLaClaveInvalidaSesionesAbiertasYElTokenEsDeUnSoloUso() throws Exception {
        Cookie sesionAbierta = login("owner@a.test");
        mvc.perform(get("/api/v1/auth/me").cookie(sesionAbierta)).andExpect(status().isOk());

        String token = requestReset();
        reset(token, NEW_PASSWORD, 204);

        mvc.perform(get("/api/v1/auth/me").cookie(sesionAbierta)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest("owner@a.test", PASSWORD)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest("owner@a.test", NEW_PASSWORD)).andExpect(status().isOk());
        reset(token, "Tercera-Clave-12345", 400);
        mvc.perform(loginRequest("owner@a.test", NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void claveNuevaDebilNoConsumeElToken() throws Exception {
        String token = requestReset();

        reset(token, "corta", 400);

        reset(token, NEW_PASSWORD, 204);
    }

    @Test
    void tokenCaducadoNoCambiaLaClave() throws Exception {
        String token = requestReset();
        jdbc.update("UPDATE account_tokens SET expires_at = now() - interval '1 minute'");

        reset(token, NEW_PASSWORD, 400);

        mvc.perform(loginRequest("owner@a.test", PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void unTokenDeVerificacionNoSirveParaRestablecerClave() throws Exception {
        member("pendiente@a.test", tenant("negocio-b"), Role.OWNER, false);
        postJson("/api/v1/auth/forgot-password", "{\"email\":\"pendiente@a.test\"}").andExpect(status().isAccepted());
        outboxProcessor.processBatch();

        assertThat(mail.last().subject()).isEqualTo("Confirma tu correo");
        reset(mail.lastToken(), NEW_PASSWORD, 400);
    }

    @Test
    void solicitudesRepetidasRespetanElEnfriamiento() throws Exception {
        requestReset();
        postJson("/api/v1/auth/forgot-password", "{\"email\":\"owner@a.test\"}").andExpect(status().isAccepted());
        outboxProcessor.processBatch();

        assertThat(mail.sent).hasSize(1);
    }

    @Test
    void unNuevoPedidoInvalidaElTokenAnterior() throws Exception {
        String viejo = requestReset();
        jdbc.update("UPDATE account_tokens SET created_at = now() - interval '5 minutes'");
        String nuevo = requestReset();

        reset(viejo, NEW_PASSWORD, 400);
        reset(nuevo, NEW_PASSWORD, 204);
    }
}

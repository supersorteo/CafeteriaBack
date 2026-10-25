package Cafeteria.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
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

class SuperAdminAuthTest extends IntegrationTestSupport {

    static final String EMAIL = "root@plataforma.test";
    static final String PASSWORD_SA = "Super-Clave-De-Prueba-1";
    static final Pattern CODE = Pattern.compile("es: (\\d{6})");

    @Autowired
    SuperAdminAuthService auth;

    @BeforeEach
    void reset() {
        auth.resetLockout();
        auth.useClock(Clock.systemUTC());
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/superadmin/auth/login"), newXsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
    }

    private String lastCode() {
        Matcher matcher = CODE.matcher(mail.last().body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private MvcResult verify(Cookie session, String code) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/superadmin/auth/verify").cookie(session), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(code))).andReturn();
    }

    private Cookie startLogin() throws Exception {
        MvcResult result = login(EMAIL, PASSWORD_SA);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getCookie("SA_SESSION");
    }

    protected Cookie superAdminSession() throws Exception {
        Cookie pending = startLogin();
        MvcResult done = verify(pending, lastCode());
        assertThat(done.getResponse().getStatus()).isEqualTo(204);
        Cookie session = done.getResponse().getCookie("SA_SESSION");
        return session != null ? session : pending;
    }

    @Test
    void laContrasenaCorrectaEnviaUnCodigoAlCorreoDelSuperadmin() throws Exception {
        MvcResult result = login(EMAIL.toUpperCase(), PASSWORD_SA);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"step\":\"code\"").contains("r***@plataforma.test");
        assertThat(mail.sent).hasSize(1);
        assertThat(mail.last().to()).isEqualTo(EMAIL);
        assertThat(lastCode()).hasSize(6);
        mvc.perform(get("/api/v1/superadmin/stats").cookie(result.getResponse().getCookie("SA_SESSION")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contrasenaOCorreoIncorrectosDanLaMismaRespuestaGenericaYNoEnvianCorreo() throws Exception {
        for (MvcResult result : new MvcResult[] { login(EMAIL, "otra-clave-larga-123"), login("otro@x.test", PASSWORD_SA) }) {
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getContentAsString()).contains("invalid_credentials");
        }
        assertThat(mail.sent).isEmpty();
    }

    @Test
    void elCodigoCorrectoConcedeAccesoAlPanel() throws Exception {
        Cookie session = superAdminSession();

        mvc.perform(get("/api/v1/superadmin/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));
        mvc.perform(get("/api/v1/superadmin/stats").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void unCodigoIncorrectoNoDaAccesoYTrasCincoFallosLaSesionSeInvalida() throws Exception {
        Cookie pending = startLogin();
        String real = lastCode();
        String wrong = real.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            assertThat(verify(pending, wrong).getResponse().getStatus()).isEqualTo(401);
        }

        assertThat(verify(pending, real).getResponse().getStatus()).isEqualTo(401);
        mvc.perform(get("/api/v1/superadmin/stats").cookie(pending)).andExpect(status().isUnauthorized());
    }

    @Test
    void sinHaberIniciadoElPrimerPasoElCodigoNoSirve() throws Exception {
        assertThat(verify(new Cookie("SA_SESSION", "inexistente"), "123456").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void elCodigoCaducadoSeRechaza() throws Exception {
        Cookie pending = startLogin();
        String code = lastCode();
        auth.useClock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(11)));

        assertThat(verify(pending, code).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void sinCsrfLasOperacionesSeRechazan() throws Exception {
        mvc.perform(post("/api/v1/superadmin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, PASSWORD_SA)))
                .andExpect(status().isForbidden());
    }

    @Test
    void tras10FallosSeguidosSeBloqueaTodoIntentoUnTiempo() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(login(EMAIL, "mala-clave-larga-" + i).getResponse().getStatus()).isEqualTo(401);
        }

        assertThat(login(EMAIL, PASSWORD_SA).getResponse().getStatus()).isEqualTo(429);
        assertThat(mail.sent).isEmpty();
    }

    @Test
    void siElCorreoFallaNoSeDejaUnaSesionPendienteYSeAvisa() throws Exception {
        mail.failing = true;

        MvcResult result = login(EMAIL, PASSWORD_SA);

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentAsString()).contains("mail_unavailable").doesNotContain(PASSWORD_SA);
    }

    @Test
    void unaSesionDeDuenoDeNegocioNoEntraAlPanelNiLaDeSuperadminALaAdministracionDeNegocios() throws Exception {
        Tenant a = tenant("negocio-a");
        member("owner@a.test", a, Role.OWNER, true);
        Cookie owner = login("owner@a.test");
        mvc.perform(get("/api/v1/superadmin/stats").cookie(owner)).andExpect(status().isUnauthorized());

        Cookie superAdmin = superAdminSession();
        mvc.perform(get("/api/v1/admin/site").cookie(superAdmin)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(superAdmin)).andExpect(status().isUnauthorized());
    }

    @Test
    void cerrarSesionInvalidaElAcceso() throws Exception {
        Cookie session = superAdminSession();

        mvc.perform(withXsrf(post("/api/v1/superadmin/auth/logout").cookie(session), newXsrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/superadmin/stats").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void laSesionDeSuperadminTieneInactividadCorta() throws Exception {
        superAdminSession();

        Long maxInactive = jdbc.queryForObject("SELECT max_inactive_interval FROM spring_session", Long.class);
        assertThat(maxInactive).isEqualTo(15 * 60);
    }
}

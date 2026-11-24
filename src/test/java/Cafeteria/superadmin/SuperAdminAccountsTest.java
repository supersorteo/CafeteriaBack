package Cafeteria.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class SuperAdminAccountsTest extends IntegrationTestSupport {

    static final String MAIN = "root@plataforma.test";
    static final String MAIN_PASSWORD = "Super-Clave-De-Prueba-1";
    static final String OTHER = "otro.admin@ejemplo.test";
    static final String TEMP_PASSWORD = "Temporal.123";
    static final String NEW_PASSWORD = "Clave-Nueva-Segura-9";
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

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/superadmin/auth/login"), newXsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
    }

    private Cookie signIn(String email, String password) throws Exception {
        MvcResult started = login(email, password);
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        Cookie pending = started.getResponse().getCookie("SA_SESSION");
        Matcher matcher = CODE.matcher(mail.last().body());
        assertThat(matcher.find()).isTrue();
        MvcResult done = mvc.perform(withXsrf(post("/api/v1/superadmin/auth/verify").cookie(pending), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(matcher.group(1)))).andReturn();
        assertThat(done.getResponse().getStatus()).isEqualTo(204);
        Cookie session = done.getResponse().getCookie("SA_SESSION");
        return session != null ? session : pending;
    }

    private SuperAdminAccounts.Account account(String role, boolean mustChange) {
        return accounts.create(OTHER, TEMP_PASSWORD, role, "AR", mustChange);
    }

    private MockHttpServletResponse changePassword(Cookie session, String current, String next) throws Exception {
        return mvc.perform(withXsrf(post("/api/v1/superadmin/auth/password").cookie(session), newXsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current\":\"%s\",\"next\":\"%s\"}".formatted(current, next))).andReturn().getResponse();
    }

    @Test
    void laCuentaPrincipalSigueEntrandoComoAntesYConTodosLosPermisos() throws Exception {
        account(SuperAdminAccounts.OPERATOR, false);

        Cookie session = signIn(MAIN, MAIN_PASSWORD);

        assertThat(mail.last().to()).isEqualTo(MAIN);
        mvc.perform(get("/api/v1/superadmin/auth/me").cookie(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(MAIN))
                .andExpect(jsonPath("$.role").value("ADMIN")).andExpect(jsonPath("$.mustChangePassword").value(false));
        mvc.perform(get("/api/v1/superadmin/codes/stats").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void laCuentaNuevaRecibeElCodigoEnSuPropioCorreoYNoEnElDelPrincipal() throws Exception {
        account(SuperAdminAccounts.OPERATOR, false);

        MvcResult started = login(OTHER.toUpperCase(), TEMP_PASSWORD);

        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        assertThat(started.getResponse().getContentAsString()).contains("o***@ejemplo.test");
        assertThat(mail.sent).hasSize(1);
        assertThat(mail.last().to()).isEqualTo(OTHER);
    }

    @Test
    void contrasenaIncorrectaOCuentaDeshabilitadaDanLaMismaRespuestaYNoEnvianCorreo() throws Exception {
        account(SuperAdminAccounts.OPERATOR, false);
        for (MvcResult result : new MvcResult[] { login(OTHER, "otra-clave-larga-1"), login("nadie@ejemplo.test", TEMP_PASSWORD) }) {
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getContentAsString()).contains("invalid_credentials");
        }
        jdbc.update("UPDATE superadmin_accounts SET enabled = FALSE");

        MvcResult disabled = login(OTHER, TEMP_PASSWORD);

        assertThat(disabled.getResponse().getStatus()).isEqualTo(401);
        assertThat(mail.sent).isEmpty();
    }

    @Test
    void elOperadorSoloLeeNegociosYActividadYNoPuedeTocarNiVerCodigos() throws Exception {
        account(SuperAdminAccounts.OPERATOR, false);
        Tenant tenant = tenant("cafe-uno");
        member("owner@uno.test", tenant, Role.OWNER, true);
        Cookie session = signIn(OTHER, TEMP_PASSWORD);

        mvc.perform(get("/api/v1/superadmin/auth/me").cookie(session))
                .andExpect(jsonPath("$.role").value("OPERATOR")).andExpect(jsonPath("$.regions[0]").value("AR"));
        mvc.perform(get("/api/v1/superadmin/stats").cookie(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/superadmin/tenants").cookie(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/superadmin/audit").cookie(session)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/superadmin/codes").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/superadmin/codes/batches").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/batches").cookie(session), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\",\"durationMonths\":1,\"quantity\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(withXsrf(post("/api/v1/superadmin/tenants/" + tenant.getId() + "/suspend").cookie(session), newXsrf()))
                .andExpect(status().isForbidden());
        mvc.perform(withXsrf(delete("/api/v1/superadmin/tenants/" + tenant.getId()).cookie(session), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmSlug\":\"cafe-uno\"}"))
                .andExpect(status().isForbidden());

        assertThat(tenants.findById(tenant.getId()).orElseThrow().getSuspendedAt()).isNull();
    }

    @Test
    void unAdministradorAdicionalTieneTodosLosPermisos() throws Exception {
        account(SuperAdminAccounts.ADMIN, false);
        Cookie session = signIn(OTHER, TEMP_PASSWORD);

        mvc.perform(get("/api/v1/superadmin/codes/stats").cookie(session)).andExpect(status().isOk());
        mvc.perform(withXsrf(post("/api/v1/superadmin/codes/batches").cookie(session), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\",\"durationMonths\":1,\"quantity\":1}"))
                .andExpect(status().isCreated());
    }

    @Test
    void conUnaContrasenaTemporalSoloPuedeCambiarlaYLuegoEntraConSusPermisos() throws Exception {
        account(SuperAdminAccounts.OPERATOR, true);
        Cookie session = signIn(OTHER, TEMP_PASSWORD);

        mvc.perform(get("/api/v1/superadmin/auth/me").cookie(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(get("/api/v1/superadmin/stats").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/superadmin/tenants").cookie(session)).andExpect(status().isForbidden());

        assertThat(changePassword(session, "equivocada-1234", NEW_PASSWORD).getStatus()).isEqualTo(400);
        assertThat(changePassword(session, TEMP_PASSWORD, "corta").getStatus()).isEqualTo(400);
        assertThat(changePassword(session, TEMP_PASSWORD, TEMP_PASSWORD).getStatus()).isEqualTo(400);
        assertThat(changePassword(session, TEMP_PASSWORD, OTHER).getStatus()).isEqualTo(400);
        MockHttpServletResponse done = changePassword(session, TEMP_PASSWORD, NEW_PASSWORD);

        assertThat(done.getStatus()).isEqualTo(204);
        Cookie renewed = done.getCookie("SA_SESSION") != null ? done.getCookie("SA_SESSION") : session;
        mvc.perform(get("/api/v1/superadmin/stats").cookie(renewed)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/superadmin/auth/me").cookie(renewed)).andExpect(jsonPath("$.mustChangePassword").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'SUPERADMIN_PASSWORD_CHANGED'", Long.class))
                .isEqualTo(1);

        assertThat(login(OTHER, TEMP_PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(OTHER, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void laContrasenaGuardadaEsUnHashYLaCuentaPrincipalNoPuedeCambiarlaDesdeElPanel() throws Exception {
        account(SuperAdminAccounts.OPERATOR, false);

        assertThat(jdbc.queryForObject("SELECT password_hash FROM superadmin_accounts", String.class))
                .doesNotContain(TEMP_PASSWORD).startsWith("{");
        Cookie main = signIn(MAIN, MAIN_PASSWORD);
        assertThat(changePassword(main, MAIN_PASSWORD, NEW_PASSWORD).getStatus()).isEqualTo(400);
    }

    @Test
    void elArranqueCreaLaCuentaUnaSolaVezSinPisarLaContrasenaYaCambiada() throws Exception {
        SuperAdminProperties primary = new SuperAdminProperties(MAIN, MAIN_PASSWORD,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder());
        SuperAdminAccountBootstrap bootstrap = new SuperAdminAccountBootstrap(accounts, primary, " Nueva.Cuenta@Ejemplo.test ",
                TEMP_PASSWORD, "operator", "ar", true);

        bootstrap.run(null);
        bootstrap.run(null);

        SuperAdminAccounts.Account created = accounts.find("nueva.cuenta@ejemplo.test").orElseThrow();
        assertThat(created.role()).isEqualTo("OPERATOR");
        assertThat(created.regions()).isEqualTo("AR");
        assertThat(created.mustChangePassword()).isTrue();
        accounts.changePassword(created.id(), NEW_PASSWORD);
        bootstrap.run(null);
        assertThat(accounts.authenticate("nueva.cuenta@ejemplo.test", NEW_PASSWORD)).isPresent();
        assertThat(accounts.authenticate("nueva.cuenta@ejemplo.test", TEMP_PASSWORD)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM superadmin_accounts", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'SUPERADMIN_ACCOUNT_CREATED'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void elArranqueIgnoraLaCuentaIgualAlPrincipalYExigeCorreoYContrasenaJuntos() throws Exception {
        SuperAdminProperties primary = new SuperAdminProperties(MAIN, MAIN_PASSWORD,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder());

        new SuperAdminAccountBootstrap(accounts, primary, MAIN, TEMP_PASSWORD, "OPERATOR", "AR", true).run(null);
        new SuperAdminAccountBootstrap(accounts, primary, "", "", "OPERATOR", "AR", true).run(null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM superadmin_accounts", Long.class)).isZero();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new SuperAdminAccountBootstrap(accounts, primary, OTHER, "", "OPERATOR", "AR", true).run(null));
    }

    @Test
    void deshabilitarOCambiarElRolDeUnaCuentaCierraSuSesionAbiertaDeInmediato() throws Exception {
        account(SuperAdminAccounts.OPERATOR, false);
        Cookie session = signIn(OTHER, TEMP_PASSWORD);
        mvc.perform(get("/api/v1/superadmin/stats").cookie(session)).andExpect(status().isOk());

        jdbc.update("UPDATE superadmin_accounts SET role = 'ADMIN'");
        mvc.perform(get("/api/v1/superadmin/stats").cookie(session)).andExpect(status().isUnauthorized());

        Cookie second = signIn(OTHER, TEMP_PASSWORD);
        mvc.perform(get("/api/v1/superadmin/stats").cookie(second)).andExpect(status().isOk());
        jdbc.update("UPDATE superadmin_accounts SET enabled = FALSE");
        mvc.perform(get("/api/v1/superadmin/stats").cookie(second)).andExpect(status().isUnauthorized());
    }

    @Test
    void cambiarLaContrasenaCierraLasOtrasSesionesDeLaMismaCuenta() throws Exception {
        account(SuperAdminAccounts.OPERATOR, false);
        Cookie first = signIn(OTHER, TEMP_PASSWORD);
        Cookie second = signIn(OTHER, TEMP_PASSWORD);
        mvc.perform(get("/api/v1/superadmin/stats").cookie(first)).andExpect(status().isOk());

        MockHttpServletResponse done = changePassword(second, TEMP_PASSWORD, NEW_PASSWORD);

        assertThat(done.getStatus()).isEqualTo(204);
        Cookie renewed = done.getCookie("SA_SESSION") != null ? done.getCookie("SA_SESSION") : second;
        mvc.perform(get("/api/v1/superadmin/stats").cookie(renewed)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/superadmin/stats").cookie(first)).andExpect(status().isUnauthorized());
    }

    @Test
    void elArranqueSincronizaRolRegionesYEstadoConLaConfiguracion() throws Exception {
        SuperAdminProperties primary = new SuperAdminProperties(MAIN, MAIN_PASSWORD,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder());
        new SuperAdminAccountBootstrap(accounts, primary, OTHER, TEMP_PASSWORD, "ADMIN", "AR", true).run(null);
        assertThat(accounts.find(OTHER).orElseThrow().role()).isEqualTo("ADMIN");

        new SuperAdminAccountBootstrap(accounts, primary, OTHER, TEMP_PASSWORD, "OPERATOR", "UY", true).run(null);
        SuperAdminAccounts.Account downgraded = accounts.find(OTHER).orElseThrow();
        assertThat(downgraded.role()).isEqualTo("OPERATOR");
        assertThat(downgraded.regions()).isEqualTo("UY");

        new SuperAdminAccountBootstrap(accounts, primary, OTHER, "", "OPERATOR", "UY", false).run(null);
        assertThat(accounts.find(OTHER).orElseThrow().enabled()).isFalse();
        assertThat(login(OTHER, TEMP_PASSWORD).getResponse().getStatus()).isEqualTo(401);
    }
}

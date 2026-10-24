package Cafeteria.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class AuthFlowTest extends IntegrationTestSupport {

    @Test
    void loginValidoCreaSesionYMeDevuelveUsuario() throws Exception {
        Tenant a = tenant("negocio-a");
        member("Owner@A.test", a, Role.OWNER, true);

        Cookie session = login("owner@a.test");

        mvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("owner@a.test"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.tenant.slug").value("negocio-a"));
    }

    @Test
    void loginConPasswordIncorrectoDevuelve401() throws Exception {
        member("owner@a.test", tenant("negocio-a"), Role.OWNER, true);

        mvc.perform(loginRequest("owner@a.test", "mala"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @Test
    void loginDeUsuarioInexistenteNoRevelaLaDiferencia() throws Exception {
        mvc.perform(loginRequest("nadie@a.test", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @Test
    void cuentaSinVerificarNoInicia() throws Exception {
        member("pendiente@a.test", tenant("negocio-a"), Role.OWNER, false);

        mvc.perform(loginRequest("pendiente@a.test", PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginSinCsrfDevuelve403() throws Exception {
        member("owner@a.test", tenant("negocio-a"), Role.OWNER, true);

        mvc.perform(post("/api/v1/auth/login").param("username", "owner@a.test").param("password", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf_invalid"));
    }

    @Test
    void loginConCabeceraCsrfDistintaALaCookieDevuelve403() throws Exception {
        member("owner@a.test", tenant("negocio-a"), Role.OWNER, true);
        Cookie xsrf = newXsrf();

        mvc.perform(post("/api/v1/auth/login").cookie(xsrf).header("X-XSRF-TOKEN", "otro-valor")
                        .param("username", "owner@a.test").param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfEmiteCookieLegibleParaAngular() throws Exception {
        mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    void meSinSesionDevuelve401SinRedireccion() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void logoutInvalidaLaSesion() throws Exception {
        member("owner@a.test", tenant("negocio-a"), Role.OWNER, true);
        Cookie session = login("owner@a.test");
        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());

        mvc.perform(withXsrf(post("/api/v1/auth/logout").cookie(session), newXsrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutSinCsrfDevuelve403YNoCierraLaSesion() throws Exception {
        member("owner@a.test", tenant("negocio-a"), Role.OWNER, true);
        Cookie session = login("owner@a.test");

        mvc.perform(post("/api/v1/auth/logout").cookie(session)).andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void editorNoPuedeUsarOperacionesDeOwner() throws Exception {
        Tenant a = tenant("negocio-a");
        member("owner@a.test", a, Role.OWNER, true);
        member("editor@a.test", a, Role.EDITOR, true);
        Cookie owner = login("owner@a.test");
        Cookie editor = login("editor@a.test");

        mvc.perform(get("/api/v1/admin/probe/owner").cookie(owner)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/probe/owner").cookie(editor)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/probe/tenant").cookie(editor)).andExpect(status().isOk());
    }

    @Test
    void usuarioDeshabilitadoPierdeAccesoConSesionAbierta() throws Exception {
        member("owner@a.test", tenant("negocio-a"), Role.OWNER, true);
        Cookie session = login("owner@a.test");
        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());

        jdbc.update("UPDATE users SET enabled = false WHERE email = 'owner@a.test'");

        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
    }
}

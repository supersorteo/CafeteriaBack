package Cafeteria.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MvcResult;

import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class SessionIsolationTest extends IntegrationTestSupport {

    static final Pattern CODE = Pattern.compile("es: (\\d{6})");

    @Autowired
    SuperAdminAuthService auth;

    Cookie owner;
    Cookie superAdmin;
    MvcResult superAdminLogin;

    @BeforeEach
    void fixtures() throws Exception {
        auth.resetLockout();
        auth.useClock(Clock.systemUTC());
        Tenant a = tenant("negocio-a");
        member("owner@a.test", a, Role.OWNER, true);
        owner = login("owner@a.test");
        superAdmin = superAdminSession();
    }

    private Cookie superAdminSession() throws Exception {
        MvcResult pending = mvc.perform(withXsrf(post("/api/v1/superadmin/auth/login"), newXsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"root@plataforma.test\",\"password\":\"Super-Clave-De-Prueba-1\"}")).andReturn();
        superAdminLogin = pending;
        Matcher matcher = CODE.matcher(mail.last().body());
        assertThat(matcher.find()).isTrue();
        Cookie session = pending.getResponse().getCookie("SA_SESSION");
        MvcResult done = mvc.perform(withXsrf(post("/api/v1/superadmin/auth/verify").cookie(session), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(matcher.group(1)))).andReturn();
        Cookie renewed = done.getResponse().getCookie("SA_SESSION");
        return renewed != null ? renewed : session;
    }

    private static String sessionHeader(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("SESSION=") || h.startsWith("SA_SESSION=")).findFirst().orElseThrow();
    }

    @Test
    void laSesionDeSuperadminUsaOtraCookieSoloParaSuRutaYMasEstricta() throws Exception {
        String header = sessionHeader(superAdminLogin);

        assertThat(superAdminLogin.getResponse().getCookie("SESSION")).isNull();
        assertThat(header).startsWith("SA_SESSION=").contains("Path=/api/v1/superadmin").contains("HttpOnly")
                .contains("SameSite=Strict");
        MvcResult ownerLogin = mvc.perform(loginRequest("owner@a.test", PASSWORD)).andReturn();
        assertThat(ownerLogin.getResponse().getCookie("SA_SESSION")).isNull();
        assertThat(sessionHeader(ownerLogin)).startsWith("SESSION=").doesNotContain("Path=/api/v1/superadmin");
    }

    @Test
    void ambasSesionesConviven_ElLoginDeUnoNoCierraNiPisaAlOtro() throws Exception {
        mvc.perform(get("/api/v1/auth/me").cookie(owner)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/superadmin/auth/me").cookie(superAdmin)).andExpect(status().isOk());

        Cookie owner2 = login("owner@a.test");

        mvc.perform(get("/api/v1/superadmin/auth/me").cookie(superAdmin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(owner2)).andExpect(status().isOk());
        Cookie superAdmin2 = superAdminSession();
        mvc.perform(get("/api/v1/auth/me").cookie(owner)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(owner2)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/superadmin/auth/me").cookie(superAdmin2)).andExpect(status().isOk());
    }

    @Test
    void cerrarSesionEnUnLadoNoAfectaAlOtro() throws Exception {
        mvc.perform(withXsrf(post("/api/v1/superadmin/auth/logout").cookie(superAdmin), newXsrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/superadmin/auth/me").cookie(superAdmin)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(owner)).andExpect(status().isOk());

        Cookie again = superAdminSession();
        mvc.perform(withXsrf(post("/api/v1/auth/logout").cookie(owner), newXsrf())).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/me").cookie(owner)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/superadmin/auth/me").cookie(again)).andExpect(status().isOk());
    }

    @Test
    void unaCookieNoSirveEnElOtroLado() throws Exception {
        Cookie ownerValueAsSuperAdmin = new Cookie("SA_SESSION", owner.getValue());
        Cookie superAdminValueAsOwner = new Cookie("SESSION", superAdmin.getValue());

        mvc.perform(get("/api/v1/superadmin/stats").cookie(ownerValueAsSuperAdmin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me").cookie(superAdminValueAsOwner)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/site").cookie(superAdmin)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/superadmin/stats").cookie(owner)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/superadmin/auth/me").cookie(superAdmin)).andExpect(jsonPath("$.email").value("root@plataforma.test"));
    }
}

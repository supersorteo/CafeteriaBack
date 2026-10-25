package Cafeteria.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Clock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import Cafeteria.catalog.Category;
import Cafeteria.catalog.CategoryRepository;
import Cafeteria.catalog.MenuItem;
import Cafeteria.catalog.MenuItemRepository;
import Cafeteria.media.MediaAsset;
import Cafeteria.site.SiteSpace;
import Cafeteria.site.SiteSpaceRepository;
import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class SuperAdminTenantsTest extends IntegrationTestSupport {

    static final Pattern CODE = Pattern.compile("es: (\\d{6})");

    @Autowired
    SuperAdminAuthService auth;
    @Autowired
    CategoryRepository categories;
    @Autowired
    MenuItemRepository items;
    @Autowired
    SiteSpaceRepository spaces;

    Tenant a;
    Tenant b;
    Cookie sa;

    @BeforeEach
    void fixtures() throws Exception {
        auth.resetLockout();
        auth.useClock(Clock.systemUTC());
        a = tenant("negocio-a");
        b = tenant("negocio-b");
        member("owner@a.test", a, Role.OWNER, true);
        member("editor@a.test", a, Role.EDITOR, true);
        member("owner@b.test", b, Role.OWNER, true);
        a.setPublished(true);
        b.setPublished(true);
        tenants.save(a);
        tenants.save(b);
        sa = superAdminSession();
    }

    private Cookie superAdminSession() throws Exception {
        MvcResult pending = mvc.perform(withXsrf(post("/api/v1/superadmin/auth/login"), newXsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"root@plataforma.test\",\"password\":\"Super-Clave-De-Prueba-1\"}")).andReturn();
        Matcher matcher = CODE.matcher(mail.last().body());
        assertThat(matcher.find()).isTrue();
        Cookie session = pending.getResponse().getCookie("SA_SESSION");
        MvcResult done = mvc.perform(withXsrf(post("/api/v1/superadmin/auth/verify").cookie(session), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(matcher.group(1)))).andReturn();
        Cookie renewed = done.getResponse().getCookie("SA_SESSION");
        return renewed != null ? renewed : session;
    }

    private void suspend(Tenant tenant) throws Exception {
        mvc.perform(withXsrf(post("/api/v1/superadmin/tenants/" + tenant.getId() + "/suspend").cookie(sa), newXsrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void laListaMuestraTodosLosNegociosConSuDuenoYSePuedeBuscarYFiltrar() throws Exception {
        mvc.perform(get("/api/v1/superadmin/tenants").cookie(sa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.slug=='negocio-a')].ownerEmail").value("owner@a.test"))
                .andExpect(jsonPath("$.content[?(@.slug=='negocio-a')].editors").value(1))
                .andExpect(jsonPath("$.content[?(@.slug=='negocio-b')].ownerVerified").value(true));

        mvc.perform(get("/api/v1/superadmin/tenants").param("query", "OWNER@B").cookie(sa))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].slug").value("negocio-b"));
        mvc.perform(get("/api/v1/superadmin/tenants").param("query", "%").cookie(sa))
                .andExpect(jsonPath("$.content", hasSize(0)));

        suspend(a);
        mvc.perform(get("/api/v1/superadmin/tenants").param("status", "suspended").cookie(sa))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].suspended").value(true));
        mvc.perform(get("/api/v1/superadmin/tenants").param("status", "active").cookie(sa))
                .andExpect(jsonPath("$.content[0].slug").value("negocio-b"));
        mvc.perform(get("/api/v1/superadmin/stats").cookie(sa))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.active").value(1))
                .andExpect(jsonPath("$.suspended").value(1))
                .andExpect(jsonPath("$.published").value(1));
    }

    @Test
    void laListaSePaginaDelLadoDelServidor() throws Exception {
        mvc.perform(get("/api/v1/superadmin/tenants").param("size", "1").param("page", "1").cookie(sa))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void suspenderBloqueaElLoginLaSesionAbiertaElSitioPublicoYLasFotos() throws Exception {
        MediaAsset foto = uploadDirect(a);
        Cookie owner = login("owner@a.test");
        mvc.perform(get("/api/v1/public/sites/negocio-a")).andExpect(status().isOk());
        mvc.perform(get("/media/" + foto.getId())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(owner)).andExpect(status().isOk());

        suspend(a);

        mvc.perform(get("/api/v1/auth/me").cookie(owner)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = 'owner@a.test'",
                Long.class)).isZero();
        mvc.perform(loginRequest("owner@a.test", PASSWORD)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest("editor@a.test", PASSWORD)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/public/sites/negocio-a")).andExpect(status().isNotFound());
        mvc.perform(get("/media/" + foto.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/sites/negocio-b")).andExpect(status().isOk());
        login("owner@b.test");
    }

    @Test
    void reactivarRestauraTodoTalComoEstaba() throws Exception {
        suspend(a);
        suspend(a);

        mvc.perform(withXsrf(post("/api/v1/superadmin/tenants/" + a.getId() + "/activate").cookie(sa), newXsrf()))
                .andExpect(status().isNoContent());

        login("owner@a.test");
        mvc.perform(get("/api/v1/public/sites/negocio-a")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'TENANT_SUSPENDED'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void noSeEliminaUnNegocioActivoNiConUnaDireccionDistinta() throws Exception {
        mvc.perform(withXsrf(delete("/api/v1/superadmin/tenants/" + a.getId()).cookie(sa), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmSlug\":\"negocio-a\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_suspended"));

        suspend(a);
        mvc.perform(withXsrf(delete("/api/v1/superadmin/tenants/" + a.getId()).cookie(sa), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmSlug\":\"otro\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("slug_mismatch"));

        assertThat(tenants.findById(a.getId())).isPresent();
    }

    @Test
    void eliminarBorraNegocioDatosUsuariosArchivosSesionesYCorreosSinTocarAOtros() throws Exception {
        MediaAsset foto = uploadDirect(a);
        MediaAsset fotoEspacio = uploadDirect(a);
        MediaAsset fotoB = uploadDirect(b);
        Category category = categories.save(new Category(a.getId(), "Platos", 0));
        MenuItem item = new MenuItem(a.getId(), category.getId(), "Plato", null, BigDecimal.ONE, null, 0);
        item.setImageId(foto.getId());
        items.save(item);
        spaces.save(new SiteSpace(a.getId(), 0, "Terraza", null, fotoEspacio.getId(), true));
        jdbc.update("INSERT INTO site_stats (id, tenant_id, position, label, value) VALUES (gen_random_uuid(), ?, 0, 'Años', 3)",
                a.getId());
        Cookie owner = login("owner@a.test");
        jdbc.update("INSERT INTO email_outbox (id, type, recipient, payload, status, attempts, next_attempt_at) "
                + "VALUES (gen_random_uuid(), 'VERIFY_EMAIL', 'owner@a.test', 'x', 'PENDING', 0, now())");
        suspend(a);

        mvc.perform(withXsrf(delete("/api/v1/superadmin/tenants/" + a.getId()).cookie(sa), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmSlug\":\"negocio-a\"}"))
                .andExpect(status().isNoContent());

        assertThat(tenants.findById(a.getId())).isEmpty();
        assertThat(users.findByEmail("owner@a.test")).isEmpty();
        assertThat(users.findByEmail("editor@a.test")).isEmpty();
        for (String table : new String[] { "categories", "menu_items", "site_spaces", "site_stats", "media_assets",
                "tenant_users" }) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class,
                    a.getId())).as(table).isZero();
        }
        assertThat(Files.exists(uploadRoot().resolve(foto.getStorageKey()))).isFalse();
        assertThat(Files.exists(uploadRoot().resolve(fotoEspacio.getStorageKey()))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE recipient = 'owner@a.test'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = 'owner@a.test'",
                Long.class)).isZero();
        mvc.perform(get("/api/v1/auth/me").cookie(owner)).andExpect(status().isUnauthorized());

        assertThat(tenants.findById(b.getId())).isPresent();
        assertThat(users.findByEmail("owner@b.test")).isPresent();
        assertThat(Files.exists(uploadRoot().resolve(fotoB.getStorageKey()))).isTrue();
        mvc.perform(get("/api/v1/public/sites/negocio-b")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/superadmin/audit").cookie(sa))
                .andExpect(jsonPath("$[0].action").value("TENANT_DELETED"))
                .andExpect(jsonPath("$[0].targetLabel").value("Negocio negocio-a (negocio-a)"))
                .andExpect(jsonPath("$[0].actor").value("root@plataforma.test"));
    }

    @Test
    void sinSesionDeSuperadminNingunaOperacionDeGestionFunciona() throws Exception {
        mvc.perform(get("/api/v1/superadmin/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(withXsrf(post("/api/v1/superadmin/tenants/" + a.getId() + "/suspend"), newXsrf()))
                .andExpect(status().isUnauthorized());
        assertThat(tenants.findById(a.getId()).orElseThrow().getSuspendedAt()).isNull();
    }
}

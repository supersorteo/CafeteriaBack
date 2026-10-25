package Cafeteria.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;

@TestPropertySource(properties = "demo.site.enabled=true")
class DemoSiteSeederTest extends IntegrationTestSupport {

    @Autowired
    DemoSiteSeeder seeder;

    private long count(String table, Tenant tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, tenant.getId());
    }

    @Test
    void creaLaDemoPublicadaSinUsuariosYConTodasSusFotos() throws Exception {
        seeder.run(null);

        Tenant demo = tenants.findBySlug("la-terraza").orElseThrow();
        assertThat(demo.isPublished()).isTrue();
        assertThat(memberships.findAll()).isEmpty();
        assertThat(users.findAll()).isEmpty();
        assertThat(count("categories", demo)).isEqualTo(5);
        assertThat(count("menu_items", demo)).isEqualTo(28);
        assertThat(count("site_spaces", demo)).isEqualTo(5);
        assertThat(count("media_assets", demo)).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_items WHERE tenant_id = ? AND image_id IS NULL",
                Long.class, demo.getId())).isZero();

        mvc.perform(get("/api/v1/public/sites/la-terraza"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("La Terraza"))
                .andExpect(jsonPath("$.hero.imageUrl").exists())
                .andExpect(jsonPath("$.gallery", hasSize(3)))
                .andExpect(jsonPath("$.spaces", hasSize(5)))
                .andExpect(jsonPath("$.spaces[0].imageUrl").exists())
                .andExpect(jsonPath("$.categories", hasSize(5)))
                .andExpect(jsonPath("$.categories[0].items[0].imageUrl").exists())
                .andExpect(jsonPath("$.reservation.areas", hasSize(4)));
    }

    @Test
    void esIdempotenteYNoDuplicaNadaAlReiniciar() throws Exception {
        seeder.run(null);
        seeder.run(null);
        seeder.run(null);

        Tenant demo = tenants.findBySlug("la-terraza").orElseThrow();
        assertThat(tenants.count()).isEqualTo(1);
        assertThat(count("menu_items", demo)).isEqualTo(28);
        assertThat(count("media_assets", demo)).isEqualTo(13);
        assertThat(count("site_spaces", demo)).isEqualTo(5);
        assertThat(count("site_stats", demo)).isEqualTo(3);
    }

    @Test
    void noTocaUnNegocioRealQueYaSeLlamaIgualYTieneDueno() throws Exception {
        Tenant real = tenant("la-terraza");
        member("duenio@real.test", real, Role.OWNER, true);

        seeder.run(null);

        assertThat(count("categories", real)).isZero();
        assertThat(count("media_assets", real)).isZero();
        assertThat(count("site_spaces", real)).isZero();
        assertThat(tenants.findBySlug("la-terraza").orElseThrow().getTagline()).isNull();
    }
}

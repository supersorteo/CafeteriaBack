package Cafeteria.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class SiteContentTest extends IntegrationTestSupport {

    Tenant a;
    Cookie ownerA;
    Cookie editorA;

    @BeforeEach
    void fixtures() throws Exception {
        a = tenant("negocio-a");
        member("owner@a.test", a, Role.OWNER, true);
        member("editor@a.test", a, Role.EDITOR, true);
        ownerA = login("owner@a.test");
        editorA = login("editor@a.test");
    }

    private MockHttpServletRequestBuilder write(Cookie session, String extra) throws Exception {
        long version = tenants.findById(a.getId()).orElseThrow().getVersion();
        String json = ("{\"name\":\"Café A\",\"whatsappNumber\":\"59899111222\",\"currency\":\"UYU\",\"locale\":\"es-UY\","
                + "\"timezone\":\"America/Montevideo\",\"primaryColor\":\"#112233\",\"accentColor\":\"#445566\","
                + "\"version\":%d%s}").formatted(version, extra.isEmpty() ? "" : "," + extra);
        return withXsrf(put("/api/v1/admin/site").cookie(session), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static final String FULL = "\"descriptor\":\"Bar & Cocina\",\"tagline\":\"Desde 2012\","
            + "\"aboutTitle\":\"Tradición, *fuego*\\ny compañía\",\"footerText\":\"Pie\",\"heroCaption\":\"Una noche\","
            + "\"marqueeItems\":[\"Uno\",\"Dos\"],"
            + "\"partySizes\":[\"2\",\"4\",\"+10\"],"
            + "\"stats\":[{\"label\":\"Años\",\"value\":14},{\"label\":\"Destilados\",\"value\":120}],"
            + "\"spaces\":[{\"name\":\"Terraza\",\"description\":\"Aire libre\"},{\"name\":\"Barra\"},"
            + "{\"name\":\"Happy hour\",\"bookable\":false}]";

    @Test
    void elOwnerGuardaElContenidoYSeLeeDeVuelta() throws Exception {
        mvc.perform(write(ownerA, FULL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descriptor").value("Bar & Cocina"))
                .andExpect(jsonPath("$.aboutTitle").value("Tradición, *fuego*\ny compañía"))
                .andExpect(jsonPath("$.marqueeItems", contains("Uno", "Dos")))
                .andExpect(jsonPath("$.partySizes", contains("2", "4", "+10")))
                .andExpect(jsonPath("$.stats[1].label").value("Destilados"))
                .andExpect(jsonPath("$.stats[1].value").value(120))
                .andExpect(jsonPath("$.spaces", hasSize(3)))
                .andExpect(jsonPath("$.spaces[0].name").value("Terraza"))
                .andExpect(jsonPath("$.spaces[0].bookable").value(true))
                .andExpect(jsonPath("$.spaces[2].bookable").value(false))
                .andExpect(jsonPath("$.highlights[0].title").value("Terraza"));

        mvc.perform(get("/api/v1/admin/site").cookie(editorA))
                .andExpect(jsonPath("$.reservationAreas", contains("Terraza", "Barra")))
                .andExpect(jsonPath("$.stats", hasSize(2)));
    }

    @Test
    void guardarDeNuevoReemplazaLasListasEnVezDeAcumularlas() throws Exception {
        mvc.perform(write(ownerA, FULL)).andExpect(status().isOk());

        mvc.perform(write(ownerA, "\"marqueeItems\":[\"Solo uno\"],\"stats\":[{\"label\":\"X\",\"value\":1}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marqueeItems", contains("Solo uno")))
                .andExpect(jsonPath("$.stats", hasSize(1)))
                .andExpect(jsonPath("$.highlights", hasSize(0)))
                .andExpect(jsonPath("$.tagline").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_stats", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_spaces", Long.class)).isZero();
    }

    @Test
    void elEditorNoPuedeCambiarElContenido() throws Exception {
        mvc.perform(write(editorA, FULL)).andExpect(status().isForbidden());

        assertThat(tenants.findById(a.getId()).orElseThrow().getTagline()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_stats", Long.class)).isZero();
    }

    @Test
    void limitesYValidacionesDelContenido() throws Exception {
        String nueve = String.join(",", Collections.nCopies(9, "\"x\""));
        mvc.perform(write(ownerA, "\"marqueeItems\":[" + nueve + "]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("marqueeItems")));
        mvc.perform(write(ownerA, "\"marqueeItems\":[\"ok\",\"  \"]")).andExpect(status().isBadRequest());
        mvc.perform(write(ownerA, "\"stats\":[{\"label\":\"a\",\"value\":1},{\"label\":\"b\",\"value\":2},"
                + "{\"label\":\"c\",\"value\":3},{\"label\":\"d\",\"value\":4}]")).andExpect(status().isBadRequest());
        mvc.perform(write(ownerA, "\"stats\":[{\"label\":\"a\",\"value\":-1}]")).andExpect(status().isBadRequest());
        mvc.perform(write(ownerA, "\"stats\":[{\"label\":\"a\",\"value\":1000000}]")).andExpect(status().isBadRequest());
        mvc.perform(write(ownerA, "\"spaces\":[{\"name\":\"\",\"description\":\"d\"}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(ownerA, "\"spaces\":[{\"name\":\"" + "x".repeat(61) + "\"}]")).andExpect(status().isBadRequest());
        mvc.perform(write(ownerA, "\"spaces\":[{\"name\":\"a\",\"description\":\"" + "x".repeat(201) + "\"}]"))
                .andExpect(status().isBadRequest());
        String once = String.join(",", java.util.stream.IntStream.range(0, 11).mapToObj(i -> "{\"name\":\"E" + i + "\"}").toList());
        mvc.perform(write(ownerA, "\"spaces\":[" + once + "]")).andExpect(status().isBadRequest());
        mvc.perform(write(ownerA, "\"spaces\":[{\"name\":\"Terraza\"},{\"name\":\" terraza \"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("spaces")));
        mvc.perform(write(ownerA, "\"tagline\":\"" + "x".repeat(161) + "\"")).andExpect(status().isBadRequest());
        mvc.perform(write(ownerA, "\"partySizes\":[\"" + "9".repeat(21) + "\"]")).andExpect(status().isBadRequest());

        assertThat(tenants.findById(a.getId()).orElseThrow().getMarqueeItems()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_stats", Long.class)).isZero();
    }

    @Test
    void elSitioPublicoIncluyeElContenidoNuevo() throws Exception {
        mvc.perform(write(ownerA, FULL)).andExpect(status().isOk());
        a = tenants.findById(a.getId()).orElseThrow();
        a.setPublished(true);
        tenants.save(a);

        mvc.perform(get("/api/v1/public/sites/negocio-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descriptor").value("Bar & Cocina"))
                .andExpect(jsonPath("$.tagline").value("Desde 2012"))
                .andExpect(jsonPath("$.footerText").value("Pie"))
                .andExpect(jsonPath("$.hero.caption").value("Una noche"))
                .andExpect(jsonPath("$.about.title").value("Tradición, *fuego*\ny compañía"))
                .andExpect(jsonPath("$.marquee", contains("Uno", "Dos")))
                .andExpect(jsonPath("$.stats[0].value").value(14))
                .andExpect(jsonPath("$.highlights[0].description").value("Aire libre"))
                .andExpect(jsonPath("$.reservation.areas", contains("Terraza", "Barra")))
                .andExpect(jsonPath("$.reservation.partySizes", hasSize(3)))
                .andExpect(jsonPath("$.spaces", hasSize(3)))
                .andExpect(jsonPath("$.spaces[0].bookable").value(true))
                .andExpect(jsonPath("$.spaces[2].name").value("Happy hour"))
                .andExpect(jsonPath("$.spaces[2].bookable").value(false))
                .andExpect(jsonPath("$.theme.primaryColor").value("#112233"));
    }

    @Test
    void unNegocioNuevoSeServeVacioSinContenidoDeOtros() throws Exception {
        a = tenants.findById(a.getId()).orElseThrow();
        a.setPublished(true);
        a.setWhatsappNumber("59899111222");
        tenants.save(a);

        mvc.perform(get("/api/v1/public/sites/negocio-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marquee", hasSize(0)))
                .andExpect(jsonPath("$.stats", hasSize(0)))
                .andExpect(jsonPath("$.highlights", hasSize(0)))
                .andExpect(jsonPath("$.reservation.areas", hasSize(0)))
                .andExpect(jsonPath("$.spaces", hasSize(0)))
                .andExpect(jsonPath("$.categories", hasSize(0)))
                .andExpect(jsonPath("$.tagline").doesNotExist());
        assertThat(a.getMarqueeItems()).isEmpty();
    }
}

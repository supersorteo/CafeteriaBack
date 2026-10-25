package Cafeteria.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import Cafeteria.media.MediaAsset;
import Cafeteria.media.MediaAssetRepository;
import Cafeteria.media.MediaCleanupService;
import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class SiteSpacesTest extends IntegrationTestSupport {

    @Autowired
    MediaAssetRepository assets;
    @Autowired
    MediaCleanupService cleanup;

    Tenant a;
    Tenant b;
    Cookie ownerA;

    @BeforeEach
    void fixtures() throws Exception {
        a = tenant("negocio-a");
        b = tenant("negocio-b");
        member("owner@a.test", a, Role.OWNER, true);
        ownerA = login("owner@a.test");
    }

    private MockHttpServletRequestBuilder save(String spaces) throws Exception {
        long version = tenants.findById(a.getId()).orElseThrow().getVersion();
        String json = ("{\"name\":\"Café A\",\"whatsappNumber\":\"59899111222\",\"currency\":\"UYU\",\"locale\":\"es-UY\","
                + "\"timezone\":\"America/Montevideo\",\"primaryColor\":\"#112233\",\"accentColor\":\"#445566\","
                + "\"version\":%d,\"spaces\":%s}").formatted(version, spaces);
        return withXsrf(put("/api/v1/admin/site").cookie(ownerA), newXsrf())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private void publish() {
        a = tenants.findById(a.getId()).orElseThrow();
        a.setPublished(true);
        tenants.save(a);
    }

    @Test
    void elEspacioConFotoSeGuardaYSeSirveEnElSitioPublico() throws Exception {
        MediaAsset foto = uploadDirect(a);

        mvc.perform(save("[{\"name\":\"Terraza\",\"description\":\"Aire libre\",\"imageId\":\"%s\"},{\"name\":\"Barra\"}]"
                .formatted(foto.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaces[0].image.id").value(foto.getId().toString()))
                .andExpect(jsonPath("$.spaces[0].image.url").value("/media/" + foto.getId()))
                .andExpect(jsonPath("$.spaces[1].image").doesNotExist())
                .andExpect(jsonPath("$.spaces[1].bookable").value(true));

        publish();
        mvc.perform(get("/api/v1/public/sites/negocio-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaces", hasSize(2)))
                .andExpect(jsonPath("$.spaces[0].imageUrl").value("/media/" + foto.getId()))
                .andExpect(jsonPath("$.spaces[0].description").value("Aire libre"))
                .andExpect(jsonPath("$.spaces[1].imageUrl").doesNotExist());
    }

    @Test
    void laFotoDeOtroNegocioSeRechazaSinCambios() throws Exception {
        MediaAsset ajena = uploadDirect(b);

        mvc.perform(save("[{\"name\":\"Terraza\",\"imageId\":\"%s\"}]".formatted(ajena.getId())))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_spaces", Long.class)).isZero();
    }

    @Test
    void quitarLaFotoDelEspacioLimpiaElMedioQueQuedaLibre() throws Exception {
        MediaAsset foto = uploadDirect(a);
        mvc.perform(save("[{\"name\":\"Terraza\",\"imageId\":\"%s\"}]".formatted(foto.getId()))).andExpect(status().isOk());

        mvc.perform(save("[{\"name\":\"Terraza\"}]")).andExpect(status().isOk());

        assertThat(assets.findById(foto.getId())).isEmpty();
        assertThat(Files.exists(uploadRoot().resolve(foto.getStorageKey()))).isFalse();
    }

    @Test
    void laLimpiezaProgramadaNoBorraLaFotoDeUnEspacio() throws Exception {
        MediaAsset foto = uploadDirect(a);
        mvc.perform(save("[{\"name\":\"Terraza\",\"imageId\":\"%s\"}]".formatted(foto.getId()))).andExpect(status().isOk());
        jdbc.update("UPDATE media_assets SET created_at = now() - make_interval(hours => 100) WHERE id = ?", foto.getId());

        assertThat(cleanup.run().unreferencedRemoved()).isZero();

        assertThat(assets.findById(foto.getId())).isPresent();
    }

    @Test
    void laMismaFotoPuedeSeguirEnOtroEspacioAlReordenar() throws Exception {
        MediaAsset foto = uploadDirect(a);
        mvc.perform(save("[{\"name\":\"Terraza\",\"imageId\":\"%s\"},{\"name\":\"Barra\"}]".formatted(foto.getId())))
                .andExpect(status().isOk());

        mvc.perform(save("[{\"name\":\"Barra\"},{\"name\":\"Terraza\",\"imageId\":\"%s\"}]".formatted(foto.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaces[1].image.id").value(foto.getId().toString()));

        assertThat(assets.findById(foto.getId())).isPresent();
    }
}

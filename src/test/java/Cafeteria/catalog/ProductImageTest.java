package Cafeteria.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import Cafeteria.media.MediaAsset;
import Cafeteria.media.MediaAssetRepository;
import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class ProductImageTest extends IntegrationTestSupport {

    @Autowired
    CategoryRepository categories;
    @Autowired
    MenuItemRepository items;
    @Autowired
    MediaAssetRepository assets;

    Tenant a;
    Tenant b;
    Category cat;
    Cookie ownerA;

    @BeforeEach
    void fixtures() throws Exception {
        a = tenant("negocio-a");
        b = tenant("negocio-b");
        member("owner@a.test", a, Role.OWNER, true);
        member("owner@b.test", b, Role.OWNER, true);
        cat = categories.save(new Category(a.getId(), "Platos", 0));
        ownerA = login("owner@a.test");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String json) throws Exception {
        return withXsrf(request.cookie(ownerA), newXsrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String json(String name, Long version, String imageField) {
        return "{\"categoryId\":\"%s\",\"name\":\"%s\",\"price\":5,\"version\":%s%s}"
                .formatted(cat.getId(), name, version, imageField.isEmpty() ? "" : "," + imageField);
    }

    private MenuItem itemWith(Tenant tenant, UUID imageId) {
        MenuItem item = new MenuItem(tenant.getId(), cat.getId(), "Plato", null, BigDecimal.ONE, null, 0);
        item.setImageId(imageId);
        return items.save(item);
    }

    private boolean fileExists(MediaAsset asset) {
        return Files.exists(uploadRoot().resolve(asset.getStorageKey()));
    }

    @Test
    void crearProductoConImagenDevuelveLaUrl() throws Exception {
        MediaAsset image = uploadDirect(a);

        mvc.perform(write(post("/api/v1/admin/menu-items"), json("Con foto", null, "\"imageId\":\"" + image.getId() + "\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageId").value(image.getId().toString()))
                .andExpect(jsonPath("$.imageUrl").value("/media/" + image.getId()));
    }

    @Test
    void editarSinImagenNuevaConservaLaReferencia() throws Exception {
        MediaAsset image = uploadDirect(a);
        MenuItem item = itemWith(a, image.getId());

        mvc.perform(write(put("/api/v1/admin/menu-items/" + item.getId()), json("Renombrado", item.getVersion(), "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value(image.getId().toString()));

        assertThat(items.findById(item.getId()).orElseThrow().getImageId()).isEqualTo(image.getId());
        assertThat(fileExists(image)).isTrue();
    }

    @Test
    void reemplazarLaImagenLimpiaLaAnteriorSiNadieMasLaUsa() throws Exception {
        MediaAsset vieja = uploadDirect(a);
        MediaAsset nueva = uploadDirect(a);
        MenuItem item = itemWith(a, vieja.getId());

        mvc.perform(write(put("/api/v1/admin/menu-items/" + item.getId()),
                        json("Plato", item.getVersion(), "\"imageId\":\"" + nueva.getId() + "\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value(nueva.getId().toString()));

        assertThat(assets.findById(vieja.getId())).isEmpty();
        assertThat(fileExists(vieja)).isFalse();
        assertThat(fileExists(nueva)).isTrue();
    }

    @Test
    void reemplazarUnaImagenCompartidaNoLaBorra() throws Exception {
        MediaAsset compartida = uploadDirect(a);
        MediaAsset nueva = uploadDirect(a);
        MenuItem uno = itemWith(a, compartida.getId());
        itemWith(a, compartida.getId());

        mvc.perform(write(put("/api/v1/admin/menu-items/" + uno.getId()),
                        json("Plato", uno.getVersion(), "\"imageId\":\"" + nueva.getId() + "\"")))
                .andExpect(status().isOk());

        assertThat(assets.findById(compartida.getId())).isPresent();
        assertThat(fileExists(compartida)).isTrue();
    }

    @Test
    void quitarLaImagenEsExplicitoYLimpiaElArchivo() throws Exception {
        MediaAsset image = uploadDirect(a);
        MenuItem item = itemWith(a, image.getId());

        mvc.perform(write(put("/api/v1/admin/menu-items/" + item.getId()),
                        json("Plato", item.getVersion(), "\"removeImage\":true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").doesNotExist());

        assertThat(items.findById(item.getId()).orElseThrow().getImageId()).isNull();
        assertThat(assets.findById(image.getId())).isEmpty();
        assertThat(fileExists(image)).isFalse();
    }

    @Test
    void enviarImagenYQuitarlaALaVezSeRechaza() throws Exception {
        MediaAsset image = uploadDirect(a);
        MenuItem item = itemWith(a, image.getId());

        mvc.perform(write(put("/api/v1/admin/menu-items/" + item.getId()),
                        json("Plato", item.getVersion(), "\"imageId\":\"" + image.getId() + "\",\"removeImage\":true")))
                .andExpect(status().isBadRequest());

        assertThat(items.findById(item.getId()).orElseThrow().getImageId()).isEqualTo(image.getId());
    }

    @Test
    void imagenDeOtroNegocioSeRechazaSinMutar() throws Exception {
        MediaAsset ajena = uploadDirect(b);
        MenuItem item = itemWith(a, null);

        mvc.perform(write(post("/api/v1/admin/menu-items"), json("Cruzado", null, "\"imageId\":\"" + ajena.getId() + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
        mvc.perform(write(put("/api/v1/admin/menu-items/" + item.getId()),
                        json("Plato", item.getVersion(), "\"imageId\":\"" + ajena.getId() + "\"")))
                .andExpect(status().isBadRequest());

        assertThat(items.countByTenantId(a.getId())).isEqualTo(1);
        assertThat(items.findById(item.getId()).orElseThrow().getImageId()).isNull();
        assertThat(assets.findById(ajena.getId())).isPresent();
    }

    @Test
    void borrarElProductoLimpiaSuImagenSiQuedaLibre() throws Exception {
        MediaAsset image = uploadDirect(a);
        MenuItem item = itemWith(a, image.getId());

        mvc.perform(withXsrf(delete("/api/v1/admin/menu-items/" + item.getId()).cookie(ownerA), newXsrf()))
                .andExpect(status().isNoContent());

        assertThat(assets.findById(image.getId())).isEmpty();
        assertThat(fileExists(image)).isFalse();
    }
}

package Cafeteria.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import Cafeteria.catalog.Category;
import Cafeteria.catalog.CategoryRepository;
import Cafeteria.catalog.MenuItem;
import Cafeteria.catalog.MenuItemRepository;
import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.support.TestImages;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import jakarta.servlet.http.Cookie;

class MediaApiTest extends IntegrationTestSupport {

    @Autowired
    MediaAssetRepository assets;
    @Autowired
    CategoryRepository categories;
    @Autowired
    MenuItemRepository items;

    Tenant a;
    Tenant b;
    Cookie ownerA;

    @BeforeEach
    void fixtures() throws Exception {
        a = tenant("negocio-a");
        b = tenant("negocio-b");
        member("owner@a.test", a, Role.OWNER, true);
        member("editor@a.test", a, Role.EDITOR, true);
        member("owner@b.test", b, Role.OWNER, true);
        ownerA = login("owner@a.test");
    }

    private long filesOnDisk() throws IOException {
        if (!Files.exists(uploadRoot())) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(uploadRoot())) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void subirJpegCreaActivoListoConRutaGeneradaPorElServidor() throws Exception {
        String body = uploadFile(ownerA, "foto.jpg", "image/jpeg", TestImages.jpeg(40, 30))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType").value("image/jpeg"))
                .andExpect(jsonPath("$.width").value(40))
                .andExpect(jsonPath("$.height").value(30))
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("/media/")))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));

        MediaAsset stored = assets.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(MediaAsset.Status.READY);
        assertThat(stored.getTenantId()).isEqualTo(a.getId());
        assertThat(stored.getStorageKey()).isEqualTo(a.getId() + "/" + id + ".jpg");
        assertThat(Files.exists(uploadRoot().resolve(stored.getStorageKey()))).isTrue();
    }

    @Test
    void pngConTransparenciaSeConservaComoPng() throws Exception {
        uploadFile(ownerA, "logo.png", "image/png", TestImages.png(20, 20, true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType").value("image/png"));
    }

    @Test
    void webpSeDecodificaYSeNormaliza() throws Exception {
        uploadFile(ownerA, "foto.webp", "image/webp", TestImages.webp())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("image/jpeg"), org.hamcrest.Matchers.is("image/png"))));
    }

    @Test
    void archivoQueNoEsImagenSeRechazaAunqueDigaSerJpeg() throws Exception {
        uploadFile(ownerA, "falso.jpg", "image/jpeg", "no soy una imagen".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_image"));

        assertThat(assets.count()).isZero();
        assertThat(filesOnDisk()).isZero();
    }

    @Test
    void imagenTruncadaOCorruptaSeRechaza() throws Exception {
        byte[] jpeg = TestImages.jpeg(64, 64);
        byte[] truncada = java.util.Arrays.copyOf(jpeg, 40);

        uploadFile(ownerA, "rota.jpg", "image/jpeg", truncada).andExpect(status().isBadRequest());

        assertThat(filesOnDisk()).isZero();
    }

    @Test
    void archivoDeMasDe5MiBSeRechazaSinGuardarNada() throws Exception {
        byte[] enorme = new byte[6 * 1024 * 1024];

        uploadFile(ownerA, "enorme.jpg", "image/jpeg", enorme)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("file_too_large"));

        assertThat(filesOnDisk()).isZero();
    }

    @Test
    void imagenDeMasDe20MegapixelesSeRechaza() throws Exception {
        uploadFile(ownerA, "gigante.png", "image/png", TestImages.hugeBinaryPng(6000, 4000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_image"));

        assertThat(assets.count()).isZero();
        assertThat(filesOnDisk()).isZero();
    }

    @Test
    void nombreDeArchivoConTraversalNoInfluyeEnLaRutaFisica() throws Exception {
        uploadFile(ownerA, "../../../evil.jpg", "image/jpeg", TestImages.jpeg(10, 10)).andExpect(status().isCreated());

        MediaAsset stored = assets.findAll().get(0);
        assertThat(stored.getStorageKey()).doesNotContain("..").doesNotContain("evil");
        try (Stream<Path> files = Files.walk(uploadRoot())) {
            assertThat(files.filter(Files::isRegularFile)).allMatch(p -> p.startsWith(uploadRoot())
                    && !p.getFileName().toString().contains("evil"));
        }
        assertThat(Files.exists(uploadRoot().getParent().resolve("evil.jpg"))).isFalse();
    }

    @Test
    void laImagenSeReescribeSinMetadatos() throws Exception {
        uploadFile(ownerA, "exif.jpg", "image/jpeg", TestImages.jpegWithExif()).andExpect(status().isCreated());

        MediaAsset stored = assets.findAll().get(0);
        byte[] bytes = Files.readAllBytes(uploadRoot().resolve(stored.getStorageKey()));
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("Exif").doesNotContain("SECRETO");
    }

    @Test
    void faltaElArchivoDevuelve400() throws Exception {
        mvc.perform(withXsrf(multipart("/api/v1/admin/media").cookie(ownerA), newXsrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void subirSinCsrfOSinSesionSeRechaza() throws Exception {
        mvc.perform(multipart("/api/v1/admin/media").file(new MockMultipartFile("file", "a.jpg", "image/jpeg",
                TestImages.jpeg(8, 8))).cookie(ownerA)).andExpect(status().isForbidden());
        mvc.perform(withXsrf(multipart("/api/v1/admin/media").file(new MockMultipartFile("file", "a.jpg",
                "image/jpeg", TestImages.jpeg(8, 8))), newXsrf())).andExpect(status().isUnauthorized());

        assertThat(assets.count()).isZero();
    }

    @Test
    void descargaPublicaSoloDeActivosListosConCabecerasSeguras() throws Exception {
        MediaAsset asset = uploadDirect(b);
        byte[] onDisk = Files.readAllBytes(uploadRoot().resolve(asset.getStorageKey()));

        mvc.perform(get("/media/" + asset.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(onDisk));

        mvc.perform(get("/media/" + UUID.randomUUID())).andExpect(status().isNotFound());
        jdbc.update("UPDATE media_assets SET status = 'PENDING' WHERE id = ?", asset.getId());
        mvc.perform(get("/media/" + asset.getId())).andExpect(status().isNotFound());
    }

    @Test
    void borrarImagenEnUsoDevuelve409YConservaElArchivo() throws Exception {
        MediaAsset asset = uploadDirect(a);
        Category category = categories.save(new Category(a.getId(), "Platos", 0));
        MenuItem item = new MenuItem(a.getId(), category.getId(), "Plato", null, BigDecimal.ONE, null, 0);
        item.setImageId(asset.getId());
        items.save(item);

        mvc.perform(withXsrf(delete("/api/v1/admin/media/" + asset.getId()).cookie(ownerA), newXsrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("media_in_use"));

        assertThat(assets.findById(asset.getId())).isPresent();
        assertThat(Files.exists(uploadRoot().resolve(asset.getStorageKey()))).isTrue();
    }

    @Test
    void borrarImagenLibreEliminaFilaYArchivo() throws Exception {
        MediaAsset asset = uploadDirect(a);

        mvc.perform(withXsrf(delete("/api/v1/admin/media/" + asset.getId()).cookie(ownerA), newXsrf()))
                .andExpect(status().isNoContent());

        assertThat(assets.findById(asset.getId())).isEmpty();
        assertThat(Files.exists(uploadRoot().resolve(asset.getStorageKey()))).isFalse();
    }

    @Test
    void noSePuedeBorrarLaImagenDeOtroNegocio() throws Exception {
        MediaAsset ajena = uploadDirect(b);

        mvc.perform(withXsrf(delete("/api/v1/admin/media/" + ajena.getId()).cookie(ownerA), newXsrf()))
                .andExpect(status().isNotFound());

        assertThat(assets.findById(ajena.getId())).isPresent();
        assertThat(Files.exists(uploadRoot().resolve(ajena.getStorageKey()))).isTrue();
    }

    @Test
    void siLaBaseFallaTrasGuardarElArchivoSeCompensaBorrandoloDelDisco() {
        UUID negocioInexistente = UUID.randomUUID();

        assertThatThrownBy(() -> mediaService.upload(negocioInexistente, TestImages.jpeg(10, 10)))
                .isInstanceOf(RuntimeException.class);

        try {
            assertThat(filesOnDisk()).isZero();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertThat(assets.count()).isZero();
    }
}

package Cafeteria.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import Cafeteria.catalog.Category;
import Cafeteria.catalog.CategoryRepository;
import Cafeteria.catalog.MenuItem;
import Cafeteria.catalog.MenuItemRepository;
import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Tenant;

class MediaCleanupTest extends IntegrationTestSupport {

    @Autowired
    MediaCleanupService cleanup;
    @Autowired
    MediaAssetRepository assets;
    @Autowired
    CategoryRepository categories;
    @Autowired
    MenuItemRepository items;

    Tenant a;

    @BeforeEach
    void fixtures() {
        a = tenant("negocio-a");
    }

    private void age(MediaAsset asset, int hours) throws Exception {
        jdbc.update("UPDATE media_assets SET created_at = now() - make_interval(hours => ?) WHERE id = ?", hours,
                asset.getId());
        Files.setLastModifiedTime(uploadRoot().resolve(asset.getStorageKey()),
                FileTime.from(Instant.now().minus(hours, ChronoUnit.HOURS)));
    }

    private boolean fileExists(MediaAsset asset) {
        return Files.exists(uploadRoot().resolve(asset.getStorageKey()));
    }

    @Test
    void borraLasImagenesSinUsoMasAntiguasQueElPeriodoDeGracia() throws Exception {
        MediaAsset vieja = uploadDirect(a);
        MediaAsset reciente = uploadDirect(a);
        age(vieja, 25);

        MediaCleanupService.Result result = cleanup.run();

        assertThat(result.unreferencedRemoved()).isEqualTo(1);
        assertThat(assets.findById(vieja.getId())).isEmpty();
        assertThat(fileExists(vieja)).isFalse();
        assertThat(assets.findById(reciente.getId())).isPresent();
        assertThat(fileExists(reciente)).isTrue();
    }

    @Test
    void nuncaBorraImagenesReferenciadasAunqueSeanAntiguas() throws Exception {
        MediaAsset enUso = uploadDirect(a);
        Category cat = categories.save(new Category(a.getId(), "Platos", 0));
        MenuItem item = new MenuItem(a.getId(), cat.getId(), "Plato", null, BigDecimal.ONE, null, 0);
        item.setImageId(enUso.getId());
        items.save(item);
        age(enUso, 100);

        MediaCleanupService.Result result = cleanup.run();

        assertThat(result.unreferencedRemoved()).isZero();
        assertThat(assets.findById(enUso.getId())).isPresent();
        assertThat(fileExists(enUso)).isTrue();
    }

    @Test
    void borraArchivosHuerfanosAntiguosPeroNoLosRecientesNiLosRegistrados() throws Exception {
        MediaAsset registrada = uploadDirect(a);
        Path dir = uploadRoot().resolve(a.getId().toString());
        Path huerfanaVieja = Files.write(dir.resolve("huerfana-vieja.jpg"), new byte[] { 1, 2, 3 });
        Path huerfanaNueva = Files.write(dir.resolve("huerfana-nueva.jpg"), new byte[] { 1, 2, 3 });
        Files.setLastModifiedTime(huerfanaVieja, FileTime.from(Instant.now().minus(30, ChronoUnit.HOURS)));

        MediaCleanupService.Result result = cleanup.run();

        assertThat(result.orphanFilesRemoved()).isEqualTo(1);
        assertThat(Files.exists(huerfanaVieja)).isFalse();
        assertThat(Files.exists(huerfanaNueva)).isTrue();
        assertThat(fileExists(registrada)).isTrue();
    }

    @Test
    void esIdempotenteSiSeEjecutaDosVeces() throws Exception {
        MediaAsset vieja = uploadDirect(a);
        age(vieja, 48);

        cleanup.run();
        MediaCleanupService.Result second = cleanup.run();

        assertThat(second.unreferencedRemoved()).isZero();
        assertThat(second.orphanFilesRemoved()).isZero();
    }
}

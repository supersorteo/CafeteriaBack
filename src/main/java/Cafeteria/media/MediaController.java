package Cafeteria.media;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @GetMapping("/media/{id}")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        MediaAsset asset = media.findPublic(id);
        return media.load(asset)
                .map(resource -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(asset.getMimeType()))
                        .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(resource))
                .orElseGet(() -> asset.getPublicUrl() != null && asset.getPublicUrl().startsWith("https://")
                        ? ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION,
                                URI.create(asset.getPublicUrl()).toString()).build()
                        : ResponseEntity.notFound().build());
    }
}

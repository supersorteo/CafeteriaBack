package Cafeteria.media;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import Cafeteria.security.TenantContext;
import Cafeteria.shared.ApiException;

@RestController
@RequestMapping("/api/v1/admin/media")
public class AdminMediaController {

    public record MediaResponse(UUID id, String url, String mimeType, Integer width, Integer height, long byteSize) {
        public static MediaResponse of(MediaAsset a) {
            return new MediaResponse(a.getId(), a.getPublicUrl(), a.getMimeType(), a.getWidth(), a.getHeight(),
                    a.getByteSize());
        }
    }

    private final TenantContext tenantContext;
    private final MediaService media;

    public AdminMediaController(TenantContext tenantContext, MediaService media) {
        this.tenantContext = tenantContext;
        this.media = media;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MediaResponse upload(@RequestPart("file") MultipartFile file) {
        UUID tenantId = tenantContext.require().tenantId();
        try {
            return MediaResponse.of(media.upload(tenantId, file.getBytes()));
        } catch (IOException e) {
            throw ApiException.badRequest("invalid_image", "No se pudo leer el archivo");
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        media.delete(tenantContext.require().tenantId(), id);
    }
}

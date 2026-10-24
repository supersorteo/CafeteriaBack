package Cafeteria.media;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.io.Resource;

public interface MediaStorage {

    record StoredMedia(String storageKey, String publicUrl) {}

    record StoredFile(String storageKey, long lastModifiedMillis) {}

    StoredMedia store(UUID tenantId, UUID mediaId, byte[] content, String mimeType);

    void delete(String storageKey);

    default Optional<Resource> load(String storageKey) {
        return Optional.empty();
    }

    default List<StoredFile> listFiles() {
        return List.of();
    }
}

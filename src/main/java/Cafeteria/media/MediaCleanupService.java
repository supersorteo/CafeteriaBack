package Cafeteria.media;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MediaCleanupService {

    private static final Logger log = LoggerFactory.getLogger(MediaCleanupService.class);

    public record Result(int unreferencedRemoved, int orphanFilesRemoved) {}

    private final MediaService media;
    private final MediaStorage storage;
    private final JdbcTemplate jdbc;
    private final int graceHours;

    public MediaCleanupService(MediaService media, MediaStorage storage, JdbcTemplate jdbc,
            @Value("${media.cleanup.grace-hours:24}") int graceHours) {
        this.media = media;
        this.storage = storage;
        this.jdbc = jdbc;
        this.graceHours = graceHours;
    }

    public Result run() {
        int unreferenced = 0;
        for (MediaAsset asset : media.staleUnreferenced(graceHours)) {
            try {
                if (media.removeIfStillUnreferenced(asset.getTenantId(), asset.getId())) {
                    unreferenced++;
                }
            } catch (RuntimeException e) {
                log.warn("Limpieza de imagen {} fallida: {}", asset.getId(), e.getClass().getSimpleName());
            }
        }
        return new Result(unreferenced, removeOrphanFiles());
    }

    private int removeOrphanFiles() {
        Set<String> known = new HashSet<>(jdbc.queryForList("SELECT storage_key FROM media_assets", String.class));
        long cutoff = System.currentTimeMillis() - graceHours * 3_600_000L;
        int removed = 0;
        for (MediaStorage.StoredFile file : storage.listFiles()) {
            if (file.lastModifiedMillis() < cutoff && !known.contains(file.storageKey())) {
                media.deleteFile(file.storageKey());
                removed++;
            }
        }
        return removed;
    }
}

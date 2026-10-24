package Cafeteria.media;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import Cafeteria.shared.ApiException;
import Cafeteria.tenant.TenantRepository;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int MAX_MEDIA_PER_TENANT = 200;

    private final MediaStorage storage;
    private final ImageProcessor processor;
    private final MediaAssetRepository assets;
    private final TenantRepository tenants;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public MediaService(MediaStorage storage, ImageProcessor processor, MediaAssetRepository assets,
            TenantRepository tenants, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.storage = storage;
        this.processor = processor;
        this.assets = assets;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public MediaAsset upload(UUID tenantId, byte[] content) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("invalid_image", "El archivo está vacío");
        }
        if (content.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                    "La imagen supera los 5 MiB permitidos");
        }
        if (assets.countByTenantId(tenantId) >= MAX_MEDIA_PER_TENANT) {
            throw ApiException.conflict("limit_reached", "Se alcanzó el máximo de imágenes del negocio");
        }
        ImageProcessor.Processed image = processor.process(content);
        UUID id = UUID.randomUUID();
        MediaStorage.StoredMedia stored = storage.store(tenantId, id, image.data(), image.mimeType());
        try {
            return tx.execute(status -> assets.saveAndFlush(new MediaAsset(id, tenantId, stored.storageKey(),
                    stored.publicUrl(), image.mimeType(), image.data().length, image.width(), image.height())));
        } catch (RuntimeException e) {
            deleteQuietly(stored.storageKey());
            throw e;
        }
    }

    @Transactional
    public void delete(UUID tenantId, UUID id) {
        lockTenant(tenantId);
        MediaAsset asset = assets.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> ApiException.notFound("Imagen no encontrada"));
        if (isReferenced(tenantId, id)) {
            throw ApiException.conflict("media_in_use", "La imagen está en uso; quítala antes de eliminarla");
        }
        remove(asset);
    }

    @Transactional
    public void deleteIfUnreferenced(UUID tenantId, UUID mediaId) {
        if (mediaId == null) {
            return;
        }
        assets.findByIdAndTenantId(mediaId, tenantId).filter(a -> !isReferenced(tenantId, mediaId)).ifPresent(this::remove);
    }

    @Transactional(readOnly = true)
    public MediaAsset requireReady(UUID tenantId, UUID mediaId, String field) {
        return assets.findByIdAndTenantId(mediaId, tenantId)
                .filter(a -> a.getStatus() == MediaAsset.Status.READY)
                .orElseThrow(() -> ApiException.invalidField(field, "La imagen no existe en este negocio"));
    }

    @Transactional(readOnly = true)
    public Map<UUID, MediaAsset> byIds(UUID tenantId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return assets.findByIdInAndTenantId(Set.copyOf(ids), tenantId).stream()
                .collect(Collectors.toMap(MediaAsset::getId, Function.identity()));
    }

    @Transactional(readOnly = true)
    public MediaAsset findPublic(UUID id) {
        return assets.findById(id).filter(a -> a.getStatus() == MediaAsset.Status.READY)
                .orElseThrow(() -> ApiException.notFound("Imagen no encontrada"));
    }

    public java.util.Optional<org.springframework.core.io.Resource> load(MediaAsset asset) {
        return storage.load(asset.getStorageKey());
    }

    public boolean isReferenced(UUID tenantId, UUID mediaId) {
        Integer count = jdbc.queryForObject("SELECT (SELECT count(*) FROM menu_items WHERE tenant_id = ? AND image_id = ?) "
                + "+ (SELECT count(*) FROM site_images WHERE tenant_id = ? AND media_id = ?)",
                Integer.class, tenantId, mediaId, tenantId, mediaId);
        return count != null && count > 0;
    }

    public void lockTenant(UUID tenantId) {
        tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
    }

    List<MediaAsset> staleUnreferenced(int graceHours) {
        return jdbc.query("SELECT id FROM media_assets m WHERE m.created_at < now() - make_interval(hours => ?) "
                + "AND NOT EXISTS (SELECT 1 FROM menu_items i WHERE i.tenant_id = m.tenant_id AND i.image_id = m.id) "
                + "AND NOT EXISTS (SELECT 1 FROM site_images s WHERE s.tenant_id = m.tenant_id AND s.media_id = m.id)",
                (rs, i) -> rs.getObject("id", UUID.class), graceHours)
                .stream().map(assets::findById).flatMap(java.util.Optional::stream).toList();
    }

    boolean removeIfStillUnreferenced(UUID tenantId, UUID mediaId) {
        Boolean removed = tx.execute(status -> {
            lockTenant(tenantId);
            return assets.findByIdAndTenantId(mediaId, tenantId).filter(a -> !isReferenced(tenantId, mediaId))
                    .map(a -> {
                        remove(a);
                        return true;
                    }).orElse(false);
        });
        return Boolean.TRUE.equals(removed);
    }

    void deleteFile(String storageKey) {
        deleteQuietly(storageKey);
    }

    private void remove(MediaAsset asset) {
        assets.delete(asset);
        assets.flush();
        String key = asset.getStorageKey();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(key);
                }
            });
        } else {
            deleteQuietly(key);
        }
    }

    private void deleteQuietly(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar el archivo {}: {}", storageKey, e.getClass().getSimpleName());
        }
    }
}

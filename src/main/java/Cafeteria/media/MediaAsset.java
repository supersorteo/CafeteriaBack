package Cafeteria.media;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "media_assets")
@Getter
@NoArgsConstructor
public class MediaAsset {

    public enum Status { PENDING, READY, DELETED }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "public_url")
    private String publicUrl;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    private Integer width;
    private Integer height;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public MediaAsset(UUID id, UUID tenantId, String storageKey, String publicUrl, String mimeType, long byteSize,
            int width, int height) {
        this.id = id;
        this.tenantId = tenantId;
        this.storageKey = storageKey;
        this.publicUrl = publicUrl;
        this.mimeType = mimeType;
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.status = Status.READY;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}

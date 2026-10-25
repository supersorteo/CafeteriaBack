package Cafeteria.site;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "site_images")
@Getter
@NoArgsConstructor
public class SiteImage {

    public enum Slot { HERO, ABOUT, GALLERY, LOGO }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Slot slot;

    @Column(nullable = false)
    private int position;

    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    private String alt;

    public SiteImage(UUID tenantId, Slot slot, int position, UUID mediaId, String alt) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.slot = slot;
        this.position = position;
        this.mediaId = mediaId;
        this.alt = alt;
    }
}

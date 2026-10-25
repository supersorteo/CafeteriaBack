package Cafeteria.site;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "site_spaces")
@Getter
@NoArgsConstructor
public class SiteSpace {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "media_id")
    private UUID mediaId;

    @Column(nullable = false)
    private boolean bookable;

    public SiteSpace(UUID tenantId, int position, String name, String description, UUID mediaId, boolean bookable) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.position = position;
        this.name = name;
        this.description = description;
        this.mediaId = mediaId;
        this.bookable = bookable;
    }
}

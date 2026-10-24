package Cafeteria.site;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "site_highlights")
@Getter
@NoArgsConstructor
public class SiteHighlight {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String title;

    private String description;

    public SiteHighlight(UUID tenantId, int position, String title, String description) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.position = position;
        this.title = title;
        this.description = description;
    }
}

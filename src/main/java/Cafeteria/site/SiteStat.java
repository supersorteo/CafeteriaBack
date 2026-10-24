package Cafeteria.site;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "site_stats")
@Getter
@NoArgsConstructor
public class SiteStat {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private int value;

    public SiteStat(UUID tenantId, int position, String label, int value) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.position = position;
        this.label = label;
        this.value = value;
    }
}

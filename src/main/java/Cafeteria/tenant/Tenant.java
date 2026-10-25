package Cafeteria.tenant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean published;

    @Column(name = "whatsapp_number")
    private String whatsappNumber;

    private String phone;
    private String email;
    private String address;
    private String schedule;

    @Column(nullable = false)
    private String currency = "UYU";

    @Column(nullable = false)
    private String locale = "es-UY";

    @Column(nullable = false)
    private String timezone = "America/Montevideo";

    @Column(name = "primary_color", nullable = false)
    private String primaryColor = "#e4572e";

    @Column(name = "accent_color", nullable = false)
    private String accentColor = "#17bebb";

    @Column(name = "hero_title")
    private String heroTitle;

    @Column(name = "hero_description")
    private String heroDescription;

    @Column(name = "about_text")
    private String aboutText;

    private String descriptor;
    private String tagline;

    @Column(name = "about_title")
    private String aboutTitle;

    @Column(name = "footer_text")
    private String footerText;

    @Column(name = "hero_caption")
    private String heroCaption;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "marquee_items", nullable = false, columnDefinition = "text[]")
    private List<String> marqueeItems = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "reservation_areas", nullable = false, columnDefinition = "text[]")
    private List<String> reservationAreas = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "party_sizes", nullable = false, columnDefinition = "text[]")
    private List<String> partySizes = new ArrayList<>();

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Tenant(String slug, String name) {
        this.id = UUID.randomUUID();
        this.slug = slug;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

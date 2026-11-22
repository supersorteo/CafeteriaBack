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

    @Column(name = "plan_expires_at")
    private Instant planExpiresAt;

    @Column(name = "plan_months")
    private Integer planMonths;

    @Column(name = "business_title_color")
    private String businessTitleColor;

    @Column(name = "background_color")
    private String backgroundColor;

    @Column(name = "footer_color")
    private String footerColor;

    @Column(name = "heading_color")
    private String headingColor;

    @Column(name = "paragraph_color")
    private String paragraphColor;

    @Column(name = "whatsapp_color")
    private String whatsappColor;

    @Column(name = "marquee_color")
    private String marqueeColor;

    @Column(name = "font_family")
    private String fontFamily;

    @Column(name = "qr_dot_type")
    private String qrDotType;

    @Column(name = "qr_corner_type")
    private String qrCornerType;

    @Column(name = "qr_dot_color")
    private String qrDotColor;

    @Column(name = "qr_background_mode")
    private String qrBackgroundMode;

    @Column(name = "qr_background_color")
    private String qrBackgroundColor;

    @Column(name = "qr_use_logo", nullable = false)
    private boolean qrUseLogo;

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

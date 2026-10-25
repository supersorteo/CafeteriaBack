package Cafeteria.site;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class SiteDtos {

    private SiteDtos() {}

    public record StatRequest(
            @NotBlank @Size(max = 40) String label,
            @NotNull @Min(0) @Max(999999) Integer value) {}

    public record SpaceRequest(
            @NotBlank @Size(max = 60) String name,
            @Size(max = 200) String description,
            UUID imageId,
            Boolean bookable) {}

    public record SiteSettingsRequest(
            @NotBlank @Size(max = 120) String name,
            @Pattern(regexp = "^[0-9]{8,15}$", message = "Debe tener entre 8 y 15 dígitos, sin + ni espacios") String whatsappNumber,
            @Size(max = 30) String phone,
            @Email @Size(max = 254) String email,
            @Size(max = 255) String address,
            @Size(max = 500) String schedule,
            @NotNull @Pattern(regexp = "UYU|USD|MXN|EUR") String currency,
            @NotNull @Pattern(regexp = "es-UY|es-MX|es-ES") String locale,
            @NotBlank @Size(max = 64) String timezone,
            @NotNull @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String primaryColor,
            @NotNull @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String accentColor,
            @Size(max = 160) String heroTitle,
            @Size(max = 600) String heroDescription,
            @Size(max = 2000) String aboutText,
            Long version,
            @Size(max = 60) String descriptor,
            @Size(max = 160) String tagline,
            @Size(max = 120) String aboutTitle,
            @Size(max = 200) String footerText,
            @Size(max = 80) String heroCaption,
            @Size(max = 8) List<@NotBlank @Size(max = 80) String> marqueeItems,
            @Size(max = 10) List<@NotBlank @Size(max = 20) String> partySizes,
            @Size(max = 3) List<@Valid StatRequest> stats,
            @Size(max = 10) List<@Valid SpaceRequest> spaces) {}

    public record ImageRef(UUID id, String url) {}

    public record GalleryRef(UUID id, String url, String alt) {}

    public record SiteImagesResponse(ImageRef hero, List<ImageRef> about, List<GalleryRef> gallery, ImageRef logo) {}

    public record StatRef(String label, int value) {}

    public record HighlightRef(String title, String description) {}

    public record SpaceRef(UUID id, String name, String description, ImageRef image, boolean bookable) {}

    public record SiteSettingsResponse(String slug, boolean published, String name, String whatsappNumber, String phone,
            String email, String address, String schedule, String currency, String locale, String timezone,
            String primaryColor, String accentColor, String heroTitle, String heroDescription, String aboutText,
            long version, SiteImagesResponse images, String descriptor, String tagline, String aboutTitle,
            String footerText, String heroCaption, List<String> marqueeItems, List<String> reservationAreas,
            List<String> partySizes, List<StatRef> stats, List<HighlightRef> highlights, List<SpaceRef> spaces) {}

    public record GalleryImageRequest(@NotNull UUID mediaId, @Size(max = 200) String alt) {}

    public record SiteImagesRequest(
            UUID hero,
            @Size(max = 3) List<UUID> about,
            @Size(max = 6) List<@Valid GalleryImageRequest> gallery,
            UUID logo) {}

    public record PublicationRequest(@NotNull Boolean published, Long version) {}

    public record DashboardResponse(String slug, String name, boolean published, long categories, long activeItems,
            long inactiveItems, long images) {}
}

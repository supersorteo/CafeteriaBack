package Cafeteria.site;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.catalog.CategoryRepository;
import Cafeteria.catalog.MenuItemRepository;
import Cafeteria.media.MediaAsset;
import Cafeteria.media.MediaAssetRepository;
import Cafeteria.media.MediaService;
import Cafeteria.plan.Entitlements;
import Cafeteria.shared.ApiException;
import Cafeteria.site.SiteDtos.DashboardResponse;
import Cafeteria.site.SiteDtos.GalleryImageRequest;
import Cafeteria.site.SiteDtos.GalleryRef;
import Cafeteria.site.SiteDtos.ImageRef;
import Cafeteria.site.SiteDtos.PublicationRequest;
import Cafeteria.site.SiteDtos.SiteImagesRequest;
import Cafeteria.site.SiteDtos.SiteImagesResponse;
import Cafeteria.site.SiteDtos.SiteSettingsRequest;
import Cafeteria.site.SiteDtos.SiteSettingsResponse;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;

@Service
@Transactional
public class SiteService {

    private final TenantRepository tenants;
    private final SiteImageRepository siteImages;
    private final SiteStatRepository siteStats;
    private final SiteSpaceRepository siteSpaces;
    private final MediaService media;
    private final MediaAssetRepository assets;
    private final CategoryRepository categories;
    private final MenuItemRepository items;
    private final Entitlements entitlements;

    public SiteService(TenantRepository tenants, SiteImageRepository siteImages, SiteStatRepository siteStats,
            SiteSpaceRepository siteSpaces, MediaService media, MediaAssetRepository assets,
            CategoryRepository categories, MenuItemRepository items, Entitlements entitlements) {
        this.entitlements = entitlements;
        this.tenants = tenants;
        this.siteImages = siteImages;
        this.siteStats = siteStats;
        this.siteSpaces = siteSpaces;
        this.media = media;
        this.assets = assets;
        this.categories = categories;
        this.items = items;
    }

    @Transactional(readOnly = true)
    public SiteSettingsResponse get(UUID tenantId) {
        return respond(find(tenantId));
    }

    public SiteSettingsResponse update(UUID tenantId, SiteSettingsRequest request) {
        Tenant tenant = find(tenantId);
        if (request.version() == null) {
            throw ApiException.badRequest("version_required", "Falta la versión del registro");
        }
        if (!Objects.equals(request.version(), tenant.getVersion())) {
            throw ApiException.conflict("version_conflict",
                    "El registro fue modificado por otra persona. Recarga e inténtalo de nuevo.");
        }
        try {
            ZoneId.of(request.timezone());
        } catch (DateTimeException e) {
            throw ApiException.invalidField("timezone", "Zona horaria inválida");
        }
        tenant.setName(request.name().strip());
        tenant.setWhatsappNumber(blankToNull(request.whatsappNumber()));
        tenant.setPhone(blankToNull(request.phone()));
        tenant.setEmail(blankToNull(request.email()));
        tenant.setAddress(blankToNull(request.address()));
        tenant.setSchedule(blankToNull(request.schedule()));
        tenant.setCurrency(request.currency());
        tenant.setLocale(request.locale());
        tenant.setTimezone(request.timezone());
        requireBrandAllowed(tenant, request);
        tenant.setPrimaryColor(request.primaryColor());
        tenant.setAccentColor(request.accentColor());
        tenant.setHeroTitle(blankToNull(request.heroTitle()));
        tenant.setHeroDescription(blankToNull(request.heroDescription()));
        tenant.setAboutText(blankToNull(request.aboutText()));
        tenant.setDescriptor(blankToNull(request.descriptor()));
        tenant.setTagline(blankToNull(request.tagline()));
        tenant.setAboutTitle(blankToNull(request.aboutTitle()));
        tenant.setFooterText(blankToNull(request.footerText()));
        tenant.setHeroCaption(blankToNull(request.heroCaption()));
        tenant.setMarqueeItems(strip(request.marqueeItems()));
        tenant.setPartySizes(strip(request.partySizes()));
        tenant.setBusinessTitleColor(hexOrNull(request.businessTitleColor()));
        tenant.setBackgroundColor(hexOrNull(request.backgroundColor()));
        tenant.setFooterColor(hexOrNull(request.footerColor()));
        tenant.setHeadingColor(hexOrNull(request.headingColor()));
        tenant.setParagraphColor(hexOrNull(request.paragraphColor()));
        tenant.setWhatsappColor(hexOrNull(request.whatsappColor()));
        tenant.setMarqueeColor(hexOrNull(request.marqueeColor()));
        tenant.setFontFamily(blankToNull(request.fontFamily()));
        Tenant saved = tenants.saveAndFlush(tenant);
        replaceStats(tenantId, request.stats());
        replaceSpaces(tenantId, request.spaces());
        return respond(saved);
    }

    private void replaceStats(UUID tenantId, List<SiteDtos.StatRequest> stats) {
        siteStats.deleteAllByTenantId(tenantId);
        List<SiteDtos.StatRequest> source = stats == null ? List.of() : stats;
        List<SiteStat> rows = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            rows.add(new SiteStat(tenantId, i, source.get(i).label().strip(), source.get(i).value()));
        }
        siteStats.saveAllAndFlush(rows);
    }

    private void replaceSpaces(UUID tenantId, List<SiteDtos.SpaceRequest> spaces) {
        List<SiteDtos.SpaceRequest> source = spaces == null ? List.of() : spaces;
        Set<String> names = new HashSet<>();
        for (SiteDtos.SpaceRequest space : source) {
            if (!names.add(space.name().strip().toLowerCase())) {
                throw ApiException.invalidField("spaces", "Dos espacios no pueden llamarse igual: " + space.name().strip());
            }
        }
        Set<UUID> requested = source.stream().map(SiteDtos.SpaceRequest::imageId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        media.lockTenant(tenantId);
        Map<UUID, MediaAsset> found = media.byIds(tenantId, requested);
        for (UUID id : requested) {
            MediaAsset asset = found.get(id);
            if (asset == null || asset.getStatus() != MediaAsset.Status.READY) {
                throw ApiException.invalidField("spaces", "La imagen " + id + " no existe en este negocio");
            }
        }

        Set<UUID> previous = siteSpaces.findByTenantIdOrderByPositionAsc(tenantId).stream()
                .map(SiteSpace::getMediaId).filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        siteSpaces.deleteAllByTenantId(tenantId);
        List<SiteSpace> rows = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            SiteDtos.SpaceRequest space = source.get(i);
            rows.add(new SiteSpace(tenantId, i, space.name().strip(), blankToNull(space.description()), space.imageId(),
                    space.bookable() == null || space.bookable()));
        }
        siteSpaces.saveAllAndFlush(rows);

        previous.removeAll(requested);
        previous.forEach(id -> media.deleteIfUnreferenced(tenantId, id));
    }

    private static List<String> strip(List<String> values) {
        return values == null ? new ArrayList<>() : values.stream().map(String::strip).collect(Collectors.toCollection(ArrayList::new));
    }

    public SiteSettingsResponse setPublication(UUID tenantId, PublicationRequest request) {
        Tenant tenant = find(tenantId);
        if (request.version() != null && !Objects.equals(request.version(), tenant.getVersion())) {
            throw ApiException.conflict("version_conflict",
                    "El registro fue modificado por otra persona. Recarga e inténtalo de nuevo.");
        }
        if (request.published()) {
            List<ApiException.FieldError> missing = new ArrayList<>();
            if (tenant.getWhatsappNumber() == null) {
                missing.add(new ApiException.FieldError("whatsappNumber", "Obligatorio para publicar"));
            }
            if (!missing.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_publish", "Faltan datos para publicar", missing);
            }
        }
        tenant.setPublished(request.published());
        return respond(tenants.saveAndFlush(tenant));
    }

    public SiteSettingsResponse setImages(UUID tenantId, SiteImagesRequest request) {
        media.lockTenant(tenantId);
        Tenant tenant = find(tenantId);
        List<UUID> about = request.about() == null ? List.of() : request.about();
        List<GalleryImageRequest> gallery = request.gallery() == null ? List.of() : request.gallery();
        int maxGallery = entitlements.limitsOf(tenant).maxGallery();
        if (gallery.size() > maxGallery) {
            throw Entitlements.limitReached("fotos en la galería", maxGallery);
        }

        Set<UUID> requested = new HashSet<>(about);
        gallery.forEach(g -> requested.add(g.mediaId()));
        if (request.hero() != null) {
            requested.add(request.hero());
        }
        if (request.logo() != null) {
            requested.add(request.logo());
        }
        Map<UUID, MediaAsset> found = media.byIds(tenantId, requested);
        for (UUID id : requested) {
            MediaAsset asset = found.get(id);
            if (asset == null || asset.getStatus() != MediaAsset.Status.READY) {
                throw ApiException.invalidField("images", "La imagen " + id + " no existe en este negocio");
            }
        }

        Set<UUID> previous = new HashSet<>();
        siteImages.findByTenantIdOrderBySlotAscPositionAsc(tenantId).forEach(s -> previous.add(s.getMediaId()));
        siteImages.deleteAllByTenantId(tenantId);

        List<SiteImage> rows = new ArrayList<>();
        if (request.hero() != null) {
            rows.add(new SiteImage(tenantId, SiteImage.Slot.HERO, 0, request.hero(), null));
        }
        if (request.logo() != null) {
            rows.add(new SiteImage(tenantId, SiteImage.Slot.LOGO, 0, request.logo(), null));
        }
        for (int i = 0; i < about.size(); i++) {
            rows.add(new SiteImage(tenantId, SiteImage.Slot.ABOUT, i, about.get(i), null));
        }
        for (int i = 0; i < gallery.size(); i++) {
            rows.add(new SiteImage(tenantId, SiteImage.Slot.GALLERY, i, gallery.get(i).mediaId(),
                    blankToNull(gallery.get(i).alt())));
        }
        siteImages.saveAllAndFlush(rows);

        previous.removeAll(requested);
        previous.forEach(id -> media.deleteIfUnreferenced(tenantId, id));
        return respond(tenant);
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(UUID tenantId) {
        Tenant tenant = find(tenantId);
        long total = items.countByTenantId(tenantId);
        long active = items.countByTenantIdAndActiveTrue(tenantId);
        return new DashboardResponse(tenant.getSlug(), tenant.getName(), tenant.isPublished(),
                categories.countByTenantId(tenantId), active, total - active, assets.countByTenantId(tenantId));
    }

    private SiteSettingsResponse respond(Tenant t) {
        List<SiteDtos.SpaceRef> spaces = spacesOf(t.getId());
        return new SiteSettingsResponse(t.getSlug(), t.isPublished(), t.getName(), t.getWhatsappNumber(), t.getPhone(),
                t.getEmail(), t.getAddress(), t.getSchedule(), t.getCurrency(), t.getLocale(), t.getTimezone(),
                t.getPrimaryColor(), t.getAccentColor(), t.getHeroTitle(), t.getHeroDescription(), t.getAboutText(),
                t.getVersion(), imagesOf(t.getId()), t.getDescriptor(), t.getTagline(), t.getAboutTitle(),
                t.getFooterText(), t.getHeroCaption(), List.copyOf(t.getMarqueeItems()),
                spaces.stream().filter(SiteDtos.SpaceRef::bookable).map(SiteDtos.SpaceRef::name).toList(),
                List.copyOf(t.getPartySizes()),
                siteStats.findByTenantIdOrderByPositionAsc(t.getId()).stream()
                        .map(s -> new SiteDtos.StatRef(s.getLabel(), s.getValue())).toList(),
                spaces.stream().map(sp -> new SiteDtos.HighlightRef(sp.name(), sp.description())).toList(), spaces,
                t.getBusinessTitleColor(), t.getBackgroundColor(), t.getFooterColor(), t.getHeadingColor(),
                t.getParagraphColor(), t.getWhatsappColor(), t.getMarqueeColor(), t.getFontFamily());
    }

    @Transactional(readOnly = true)
    public String logoUrl(UUID tenantId) {
        return siteImages.findByTenantIdOrderBySlotAscPositionAsc(tenantId).stream()
                .filter(row -> row.getSlot() == SiteImage.Slot.LOGO).findFirst()
                .flatMap(row -> Optional.ofNullable(media.byIds(tenantId, List.of(row.getMediaId())).get(row.getMediaId())))
                .map(MediaAsset::getPublicUrl).orElse(null);
    }

    private List<SiteDtos.SpaceRef> spacesOf(UUID tenantId) {
        List<SiteSpace> rows = siteSpaces.findByTenantIdOrderByPositionAsc(tenantId);
        Map<UUID, MediaAsset> byId = media.byIds(tenantId,
                rows.stream().map(SiteSpace::getMediaId).filter(Objects::nonNull).toList());
        return rows.stream().map(row -> {
            MediaAsset asset = row.getMediaId() == null ? null : byId.get(row.getMediaId());
            return new SiteDtos.SpaceRef(row.getId(), row.getName(), row.getDescription(),
                    asset == null ? null : new ImageRef(asset.getId(), asset.getPublicUrl()), row.isBookable());
        }).toList();
    }

    private SiteImagesResponse imagesOf(UUID tenantId) {
        List<SiteImage> rows = siteImages.findByTenantIdOrderBySlotAscPositionAsc(tenantId);
        Map<UUID, MediaAsset> byId = media.byIds(tenantId, rows.stream().map(SiteImage::getMediaId).toList());
        ImageRef hero = null;
        ImageRef logo = null;
        List<ImageRef> about = new ArrayList<>();
        List<GalleryRef> gallery = new ArrayList<>();
        for (SiteImage row : rows) {
            MediaAsset asset = byId.get(row.getMediaId());
            if (asset == null) {
                continue;
            }
            switch (row.getSlot()) {
                case HERO -> hero = new ImageRef(asset.getId(), asset.getPublicUrl());
                case LOGO -> logo = new ImageRef(asset.getId(), asset.getPublicUrl());
                case ABOUT -> about.add(new ImageRef(asset.getId(), asset.getPublicUrl()));
                case GALLERY -> gallery.add(new GalleryRef(asset.getId(), asset.getPublicUrl(), row.getAlt()));
            }
        }
        return new SiteImagesResponse(hero, about, gallery, logo);
    }

    private Tenant find(UUID tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
    }

    private void requireBrandAllowed(Tenant tenant, SiteSettingsRequest r) {
        if (entitlements.limitsOf(tenant).fullBranding()) {
            return;
        }
        boolean changed = differs(tenant.getBusinessTitleColor(), hexOrNull(r.businessTitleColor()))
                || differs(tenant.getBackgroundColor(), hexOrNull(r.backgroundColor()))
                || differs(tenant.getFooterColor(), hexOrNull(r.footerColor()))
                || differs(tenant.getHeadingColor(), hexOrNull(r.headingColor()))
                || differs(tenant.getParagraphColor(), hexOrNull(r.paragraphColor()))
                || differs(tenant.getWhatsappColor(), hexOrNull(r.whatsappColor()))
                || differs(tenant.getMarqueeColor(), hexOrNull(r.marqueeColor()))
                || differs(tenant.getFontFamily(), blankToNull(r.fontFamily()));
        if (changed) {
            throw Entitlements.featureUnavailable("La personalización completa de la marca");
        }
    }

    private static boolean differs(String stored, String requested) {
        return !java.util.Objects.equals(stored, requested);
    }

    private static String hexOrNull(String value) {
        String clean = blankToNull(value);
        return clean == null ? null : clean.toUpperCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

package Cafeteria.publicsite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.catalog.Category;
import Cafeteria.catalog.CategoryRepository;
import Cafeteria.catalog.MenuItem;
import Cafeteria.catalog.MenuItemRepository;
import Cafeteria.media.MediaAsset;
import Cafeteria.media.MediaService;
import Cafeteria.publicsite.PublicSiteDtos.About;
import Cafeteria.publicsite.PublicSiteDtos.Contact;
import Cafeteria.publicsite.PublicSiteDtos.GalleryItem;
import Cafeteria.publicsite.PublicSiteDtos.Hero;
import Cafeteria.publicsite.PublicSiteDtos.Highlight;
import Cafeteria.publicsite.PublicSiteDtos.Reservation;
import Cafeteria.publicsite.PublicSiteDtos.Stat;
import Cafeteria.publicsite.PublicSiteDtos.Item;
import Cafeteria.publicsite.PublicSiteDtos.PublicCategory;
import Cafeteria.publicsite.PublicSiteDtos.PublicSite;
import Cafeteria.publicsite.PublicSiteDtos.Theme;
import Cafeteria.shared.ApiException;
import Cafeteria.site.SiteHighlightRepository;
import Cafeteria.site.SiteImage;
import Cafeteria.site.SiteImageRepository;
import Cafeteria.site.SiteStatRepository;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;

@Service
public class PublicSiteService {

    private final TenantRepository tenants;
    private final CategoryRepository categories;
    private final MenuItemRepository items;
    private final SiteImageRepository siteImages;
    private final SiteStatRepository siteStats;
    private final SiteHighlightRepository siteHighlights;
    private final MediaService media;

    public PublicSiteService(TenantRepository tenants, CategoryRepository categories, MenuItemRepository items,
            SiteImageRepository siteImages, SiteStatRepository siteStats, SiteHighlightRepository siteHighlights,
            MediaService media) {
        this.tenants = tenants;
        this.categories = categories;
        this.items = items;
        this.siteImages = siteImages;
        this.siteStats = siteStats;
        this.siteHighlights = siteHighlights;
        this.media = media;
    }

    @Transactional(readOnly = true)
    public PublicSite get(String slug) {
        Tenant tenant = tenants.findBySlug(slug).filter(Tenant::isPublished)
                .orElseThrow(() -> ApiException.notFound("Sitio no encontrado"));
        UUID tenantId = tenant.getId();

        List<Category> categoryRows = categories.findByTenantIdOrderBySortOrderAscIdAsc(tenantId);
        List<MenuItem> itemRows = items.findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(tenantId);
        List<SiteImage> imageRows = siteImages.findByTenantIdOrderBySlotAscPositionAsc(tenantId);

        List<UUID> mediaIds = Stream.concat(itemRows.stream().map(MenuItem::getImageId),
                imageRows.stream().map(SiteImage::getMediaId)).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> urls = new HashMap<>();
        for (MediaAsset asset : media.byIds(tenantId, mediaIds).values()) {
            urls.put(asset.getId(), asset.getPublicUrl());
        }

        Map<UUID, List<Item>> itemsByCategory = new LinkedHashMap<>();
        for (MenuItem item : itemRows) {
            itemsByCategory.computeIfAbsent(item.getCategoryId(), k -> new ArrayList<>()).add(new Item(item.getId(),
                    item.getName(), item.getDescription(), item.getPrice(), item.getTag(), urls.get(item.getImageId())));
        }
        List<PublicCategory> publicCategories = categoryRows.stream()
                .map(c -> new PublicCategory(c.getId(), c.getName(), itemsByCategory.getOrDefault(c.getId(), List.of())))
                .toList();

        String heroUrl = null;
        List<String> aboutUrls = new ArrayList<>();
        List<GalleryItem> gallery = new ArrayList<>();
        for (SiteImage row : imageRows) {
            String url = urls.get(row.getMediaId());
            if (url == null) {
                continue;
            }
            switch (row.getSlot()) {
                case HERO -> heroUrl = url;
                case ABOUT -> aboutUrls.add(url);
                case GALLERY -> gallery.add(new GalleryItem(url, row.getAlt()));
            }
        }

        return new PublicSite(tenant.getSlug(), tenant.getName(), tenant.getDescriptor(), tenant.getTagline(),
                tenant.getFooterText(), tenant.getCurrency(), tenant.getLocale(), tenant.getTimezone(),
                new Theme(tenant.getPrimaryColor(), tenant.getAccentColor()),
                new Contact(tenant.getWhatsappNumber(), tenant.getPhone(), tenant.getEmail(), tenant.getAddress(),
                        tenant.getSchedule()),
                new Hero(tenant.getHeroTitle(), tenant.getHeroDescription(), tenant.getHeroCaption(), heroUrl),
                new About(tenant.getAboutTitle(), tenant.getAboutText(), aboutUrls), gallery,
                List.copyOf(tenant.getMarqueeItems()),
                siteStats.findByTenantIdOrderByPositionAsc(tenantId).stream()
                        .map(st -> new Stat(st.getLabel(), st.getValue())).toList(),
                siteHighlights.findByTenantIdOrderByPositionAsc(tenantId).stream()
                        .map(h -> new Highlight(h.getTitle(), h.getDescription())).toList(),
                new Reservation(List.copyOf(tenant.getReservationAreas()), List.copyOf(tenant.getPartySizes())),
                publicCategories);
    }
}

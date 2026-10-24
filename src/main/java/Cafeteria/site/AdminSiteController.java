package Cafeteria.site;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.security.TenantContext;
import Cafeteria.site.SiteDtos.DashboardResponse;
import Cafeteria.site.SiteDtos.PublicationRequest;
import Cafeteria.site.SiteDtos.SiteImagesRequest;
import Cafeteria.site.SiteDtos.SiteSettingsRequest;
import Cafeteria.site.SiteDtos.SiteSettingsResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminSiteController {

    private final TenantContext tenantContext;
    private final SiteService site;

    public AdminSiteController(TenantContext tenantContext, SiteService site) {
        this.tenantContext = tenantContext;
        this.site = site;
    }

    @GetMapping("/site")
    public SiteSettingsResponse get() {
        return site.get(tenantContext.require().tenantId());
    }

    @PutMapping("/site")
    public SiteSettingsResponse update(@Valid @RequestBody SiteSettingsRequest request) {
        return site.update(tenantContext.requireOwner().tenantId(), request);
    }

    @PutMapping("/site/images")
    public SiteSettingsResponse setImages(@Valid @RequestBody SiteImagesRequest request) {
        return site.setImages(tenantContext.requireOwner().tenantId(), request);
    }

    @PutMapping("/site/publication")
    public SiteSettingsResponse setPublication(@Valid @RequestBody PublicationRequest request) {
        return site.setPublication(tenantContext.requireOwner().tenantId(), request);
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return site.dashboard(tenantContext.require().tenantId());
    }
}

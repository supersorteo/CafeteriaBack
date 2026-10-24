package Cafeteria.publicsite;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.publicsite.PublicSiteDtos.PublicSite;

@RestController
@RequestMapping("/api/v1/public/sites")
public class PublicSiteController {

    private final PublicSiteService service;

    public PublicSiteController(PublicSiteService service) {
        this.service = service;
    }

    @GetMapping("/{slug}")
    public PublicSite get(@PathVariable String slug) {
        return service.get(slug);
    }
}

package Cafeteria.seed;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import Cafeteria.catalog.Category;
import Cafeteria.catalog.CategoryRepository;
import Cafeteria.catalog.MenuItem;
import Cafeteria.catalog.MenuItemRepository;
import Cafeteria.media.MediaAssetRepository;
import Cafeteria.media.MediaService;
import Cafeteria.site.SiteImage;
import Cafeteria.site.SiteImageRepository;
import Cafeteria.site.SiteSpace;
import Cafeteria.site.SiteSpaceRepository;
import Cafeteria.site.SiteStat;
import Cafeteria.site.SiteStatRepository;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;

@Component
public class DemoSiteBuilder {

    private static final Logger log = LoggerFactory.getLogger(DemoSiteBuilder.class);

    public record Item(String name, String description, String price, String tag) {}

    public record Section(String name, List<Item> items) {}

    private final TenantRepository tenants;
    private final CategoryRepository categories;
    private final MenuItemRepository menuItems;
    private final MediaService media;
    private final MediaAssetRepository assets;
    private final SiteImageRepository siteImages;
    private final SiteStatRepository siteStats;
    private final SiteSpaceRepository siteSpaces;

    public DemoSiteBuilder(TenantRepository tenants, CategoryRepository categories, MenuItemRepository menuItems,
            MediaService media, MediaAssetRepository assets, SiteImageRepository siteImages,
            SiteStatRepository siteStats, SiteSpaceRepository siteSpaces) {
        this.tenants = tenants;
        this.categories = categories;
        this.menuItems = menuItems;
        this.media = media;
        this.assets = assets;
        this.siteImages = siteImages;
        this.siteStats = siteStats;
        this.siteSpaces = siteSpaces;
    }

    public void populate(Tenant tenant) {
        Map<String, UUID> uploaded = new HashMap<>();
        if (categories.countByTenantId(tenant.getId()) == 0) {
            importMenu(tenant, terrazaMenu());
        }
        fillDemoProfile(tenant);
        attachMenuImages(tenant, uploaded);
        attachSiteContent(tenant, uploaded);
    }

    public void importMenu(Tenant tenant, List<Section> sections) {
        int categoryOrder = 0;
        for (Section section : sections) {
            Category category = categories.save(new Category(tenant.getId(), section.name(), categoryOrder++));
            int itemOrder = 0;
            for (Item item : section.items()) {
                menuItems.save(new MenuItem(tenant.getId(), category.getId(), item.name(), item.description(),
                        new BigDecimal(item.price()), item.tag(), itemOrder++));
            }
        }
    }

    private static final Map<String, List<String>> MENU_IMAGES = Map.of(
            "Cocteles", List.of("negroni", "margarita", "old-fashioned", "negroni", "margarita", "old-fashioned"),
            "Destilados & Cervezas", List.of("old-fashioned", "negroni", "margarita", "old-fashioned", "beer", "restaurant"),
            "Entrantes", List.of("tacos", "wings", "seafood", "tacos", "wings", "tacos"),
            "Platos principales", List.of("steak", "seafood", "vegetariano", "burger", "seafood", "steak"),
            "Postres", List.of("chocolate", "cheesecake", "cheesecake", "chocolate"));

    private void fillDemoProfile(Tenant tenant) {
        if (tenant.getTagline() == null) {
            tenant.setWhatsappNumber("59896909509");
            tenant.setPrimaryColor("#FF5C39");
            tenant.setAccentColor("#0E8388");
            tenant.setDescriptor("Bar & Cocina");
            tenant.setTagline("Est. 2012 · Coctelería de autor · Cocina de temporada");
            tenant.setHeroTitle("Donde la noche\n*sabe mejor*");
            tenant.setHeroDescription("Cocteles artesanales, platos de temporada y la mejor selección de destilados. "
                    + "Vive la experiencia y reserva tu mesa directo por WhatsApp.");
            tenant.setHeroCaption("Una noche para disfrutar");
            tenant.setAboutTitle("Tradición, *fuego*\ny buena compañía");
            tenant.setAboutText("Desde 2012 servimos coctelería de autor y cocina de temporada en un espacio pensado "
                    + "para disfrutar sin prisa. Nuestro bar cuenta con más de 120 etiquetas de destilados, "
                    + "ingredientes locales y una carta que cambia con las estaciones.");
            tenant.setFooterText("Coctelería de autor y cocina de temporada desde 2012.");
            tenant.setAddress("Av. de los Cerezos 42, Centro");
            tenant.setPhone("+52 55 1234 5678");
            tenant.setEmail("hola@laterraza.bar");
            tenant.setSchedule("Martes – Jueves · 6 pm – 1 am\nViernes – Sábado · 6 pm – 3 am\nDomingo · 1 pm – 10 pm");
            tenant.setMarqueeItems(new ArrayList<>(List.of("Coctelería de autor", "Cocina de temporada",
                    "Música en vivo", "Happy hour 2x1", "Reservas por WhatsApp")));
            tenant.setPartySizes(new ArrayList<>(List.of("2", "3", "4", "5", "6", "8", "10", "+10 (evento)")));
        }
        tenants.save(tenant);
    }

    private void attachMenuImages(Tenant tenant, Map<String, UUID> uploaded) {
        if (assets.countByTenantId(tenant.getId()) > 0) {
            return;
        }
        for (Category category : categories.findByTenantIdOrderBySortOrderAscIdAsc(tenant.getId())) {
            List<String> files = MENU_IMAGES.get(category.getName());
            if (files == null) {
                continue;
            }
            List<MenuItem> list = menuItems.findByTenantIdAndCategoryIdOrderBySortOrderAscIdAsc(tenant.getId(),
                    category.getId());
            for (int i = 0; i < list.size() && i < files.size(); i++) {
                UUID mediaId = photo(tenant, uploaded, files.get(i));
                if (mediaId != null) {
                    list.get(i).setImageId(mediaId);
                    menuItems.saveAndFlush(list.get(i));
                }
            }
        }
    }

    private void attachSiteContent(Tenant tenant, Map<String, UUID> uploaded) {
        if (siteStats.findByTenantIdOrderByPositionAsc(tenant.getId()).isEmpty()) {
            siteStats.save(new SiteStat(tenant.getId(), 0, "Años de historia", 14));
            siteStats.save(new SiteStat(tenant.getId(), 1, "Destilados", 120));
            siteStats.save(new SiteStat(tenant.getId(), 2, "Platos y cocteles", 35));
        }
        if (siteSpaces.findByTenantIdOrderByPositionAsc(tenant.getId()).isEmpty()) {
            siteSpaces.save(new SiteSpace(tenant.getId(), 0, "Terraza exterior",
                    "Aire libre, luz cálida y música en vivo los viernes.", photo(tenant, uploaded, "restaurant"), true));
            siteSpaces.save(new SiteSpace(tenant.getId(), 1, "Salón interior",
                    "Ambiente tranquilo para cenar y conversar.", photo(tenant, uploaded, "steak"), true));
            siteSpaces.save(new SiteSpace(tenant.getId(), 2, "Barra",
                    "Cocteles de autor preparados frente a ti.", photo(tenant, uploaded, "negroni"), true));
            siteSpaces.save(new SiteSpace(tenant.getId(), 3, "Salón privado",
                    "Hasta 20 personas. Ideal celebraciones y eventos.", null, true));
            siteSpaces.save(new SiteSpace(tenant.getId(), 4, "Happy hour",
                    "Martes a jueves, 6 a 8 pm. 2x1 en cocteles de la casa.", null, false));
        }
        if (siteImages.findByTenantIdOrderBySlotAscPositionAsc(tenant.getId()).isEmpty()) {
            addSiteImage(tenant, uploaded, SiteImage.Slot.HERO, 0, "restaurant", "Bar cálido con una amplia selección de bebidas");
            addSiteImage(tenant, uploaded, SiteImage.Slot.ABOUT, 0, "restaurant", null);
            addSiteImage(tenant, uploaded, SiteImage.Slot.ABOUT, 1, "negroni", null);
            addSiteImage(tenant, uploaded, SiteImage.Slot.ABOUT, 2, "steak", null);
            addSiteImage(tenant, uploaded, SiteImage.Slot.GALLERY, 0, "restaurant", "Bar acogedor con luces cálidas");
            addSiteImage(tenant, uploaded, SiteImage.Slot.GALLERY, 1, "margarita", "Cóctel fresco preparado al momento");
            addSiteImage(tenant, uploaded, SiteImage.Slot.GALLERY, 2, "wings", "Botana de alitas para compartir");
        }
    }

    private void addSiteImage(Tenant tenant, Map<String, UUID> uploaded, SiteImage.Slot slot, int position, String name,
            String alt) {
        UUID mediaId = photo(tenant, uploaded, name);
        if (mediaId != null) {
            siteImages.saveAndFlush(new SiteImage(tenant.getId(), slot, position, mediaId, alt));
        }
    }

    private UUID photo(Tenant tenant, Map<String, UUID> uploaded, String name) {
        if (uploaded.containsKey(name)) {
            return uploaded.get(name);
        }
        UUID id = upload(tenant, name);
        if (id != null) {
            uploaded.put(name, id);
        }
        return id;
    }

    private UUID upload(Tenant tenant, String name) {
        try {
            byte[] bytes = new ClassPathResource("demo-images/" + name + ".jpg").getContentAsByteArray();
            return media.upload(tenant.getId(), bytes).getId();
        } catch (IOException | RuntimeException e) {
            log.warn("No se pudo importar la foto de demo {}: {}", name, e.getMessage());
            return null;
        }
    }

    private static List<Section> terrazaMenu() {
        return List.of(
                new Section("Cocteles", List.of(
                        new Item("Negroni de la Casa", "Gin, vermut rojo y bitter artesanal, servido en roca grande.", "180", "Clásico"),
                        new Item("Margarita de Mango & Tajín", "Tequila reposado, mango natural, lima y escarchado de tajín.", "160", "De temporada"),
                        new Item("Old Fashioned Ahumado", "Bourbon, bitters de cacao y humo de roble al momento.", "210", "Firma"),
                        new Item("Mezcalita de Frutos Rojos", "Mezcal joven, mermelada de frutos rojos y toque de habanero.", "175", "Picante"),
                        new Item("Espresso Martini", "Vodka, licor de café y espresso doble cremoso.", "170", "Post-cena"),
                        new Item("Spritz de la Terraza", "Aperitivo de la casa, prosecco y soda con romero quemado.", "150", "Refrescante"))),
                new Section("Destilados & Cervezas", List.of(
                        new Item("Whisky Single Malt 12 años", "2 oz · servido solo o en las rocas.", "220", "Escocia"),
                        new Item("Mezcal Artesanal Joven", "2 oz · ensamble espadín-cuishe, acompañado de sal de gusano.", "190", "Oaxaca"),
                        new Item("Tequila Reposado", "2 oz · barrica de roble blanco, notas de vainilla.", "180", "Jalisco"),
                        new Item("Ron Añejo 15 años", "2 oz · Caribe, madera dulce y especias.", "200", "Premium"),
                        new Item("Cerveza Artesanal de la Casa", "Lager o IPA de cervecería local, 355 ml.", "95", "Local"),
                        new Item("Vino de la Semana", "Copa 5 oz · selección rotativa de nuestra cava.", "140", "Cava"))),
                new Section("Entrantes", List.of(
                        new Item("Tacos de Arrachera (3 pzas)", "Tortilla de maíz, guacamole, cebolla curtida y salsa de la casa.", "165", "Para compartir"),
                        new Item("Alitas BBQ & Miel Chipotle", "500 g, aderezo ranch casero.", "180", "Favorito"),
                        new Item("Carpaccio de Salmón", "Aceite de trufa, alcaparras, rúgula y parmesano.", "190", "Fresco"),
                        new Item("Nachos La Terraza", "Totopos, queso fundido, frijoles, pico de gallo y crema.", "150", "Para compartir"),
                        new Item("Croquetas de Jamón Ibérico", "Con alioli de limón asado (6 piezas).", "145", "Entrada"),
                        new Item("Queso Fundido con Chorizo", "Queso manchego gratinado, chorizo español y tortillas.", "160", "Clásico"))),
                new Section("Platos principales", List.of(
                        new Item("Rib Eye 400 g", "Corte madurado 28 días, mantequilla de hierbas y papas rústicas.", "480", "A la parrilla"),
                        new Item("Pulpo Anticuchero", "Papa confitada, emulsión de ají amarillo y brotes.", "390", "Del mar"),
                        new Item("Risotto de Hongos", "Arborio, tres hongos, parmesano y aceite de trufa.", "320", "Vegetariano"),
                        new Item("Burger La Terraza", "200 g de res, queso cheddar madurado, tocino y papas trufadas.", "290", "Firma"),
                        new Item("Salmón a la Parrilla", "Mantequilla de miso, espárragos y limón asado.", "360", "Del mar"),
                        new Item("Costillas BBQ (1 kg)", "Cocción lenta 8 horas, salsa BBQ de la casa y elote.", "420", "Para compartir"))),
                new Section("Postres", List.of(
                        new Item("Volcán de Chocolate", "Centro líquido, helado de vainilla y frutos rojos.", "130", "Caliente"),
                        new Item("Crème Brûlée de Café", "Azúcar caramelizada y biscotti de almendra.", "120", "Clásico"),
                        new Item("Cheesecake de Maracuyá", "Base de galleta, coulis de maracuyá natural.", "125", "Ácido-dulce"),
                        new Item("Churros con Chocolate", "Canela, azúcar y chocolate espeso caliente (6 piezas).", "110", "Para compartir"))));
    }
}

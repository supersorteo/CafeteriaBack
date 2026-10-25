package Cafeteria.publicsite;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class PublicSiteDtos {

    private PublicSiteDtos() {}

    public record Contact(String whatsappNumber, String phone, String email, String address, String schedule) {}

    public record Theme(String primaryColor, String accentColor) {}

    public record Hero(String title, String description, String caption, String imageUrl) {}

    public record About(String title, String text, List<String> imageUrls) {}

    public record GalleryItem(String url, String alt) {}

    public record Stat(String label, int value) {}

    public record Highlight(String title, String description) {}

    public record Reservation(List<String> areas, List<String> partySizes) {}

    public record Item(UUID id, String name, String description, BigDecimal price, String tag, String imageUrl) {}

    public record PublicCategory(UUID id, String name, List<Item> items) {}

    public record PublicSite(String slug, String name, String descriptor, String tagline, String footerText,
            String currency, String locale, String timezone, Theme theme, Contact contact, Hero hero, About about,
            List<GalleryItem> gallery, List<String> marquee, List<Stat> stats, List<Highlight> highlights,
            Reservation reservation, List<PublicCategory> categories, String logoUrl) {}
}

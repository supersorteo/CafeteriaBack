package Cafeteria.billing;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BillingProperties {

    private final Map<String, String> whatsapp;

    public BillingProperties(@Value("${billing.whatsapp.ar:}") String ar, @Value("${billing.whatsapp.uy:}") String uy) {
        this.whatsapp = Map.of("AR", digits(ar), "UY", digits(uy));
    }

    public String whatsappOf(String region) {
        return whatsapp.getOrDefault(region, "");
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }
}

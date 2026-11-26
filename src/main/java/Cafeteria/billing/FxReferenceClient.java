package Cafeteria.billing;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FxReferenceClient {

    public record Reference(String code, String name, BigDecimal sell) {}

    private static final Logger log = LoggerFactory.getLogger(FxReferenceClient.class);
    private static final Duration CACHE = Duration.ofMinutes(30);
    private static final Map<String, String> NAMES = Map.of("oficial", "Oficial", "mayorista", "Mayorista", "bolsa", "MEP",
            "blue", "Blue");
    private static final List<String> ORDER = List.of("oficial", "mayorista", "bolsa", "blue");

    private final RestClient client;
    private final String url;
    private List<Reference> cached = List.of();
    private Instant cachedAt = Instant.EPOCH;

    public FxReferenceClient(@Value("${billing.fx-reference-url:https://dolarapi.com/v1/dolares}") String url) {
        this.url = url;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public synchronized List<Reference> references() {
        if (url.isBlank() || Instant.now().isBefore(cachedAt.plus(CACHE))) {
            return cached;
        }
        try {
            List<Map<String, Object>> rows = client.get().uri(url).retrieve().body(new ParameterizedTypeReference<>() {});
            List<Reference> found = new ArrayList<>();
            for (String code : ORDER) {
                for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
                    if (code.equals(row.get("casa")) && row.get("venta") != null) {
                        found.add(new Reference(code, NAMES.get(code), new BigDecimal(String.valueOf(row.get("venta")))));
                    }
                }
            }
            cached = found;
        } catch (RuntimeException e) {
            log.warn("No se pudieron leer las cotizaciones de referencia: {}", e.getMessage());
            cached = List.of();
        }
        cachedAt = Instant.now();
        return cached;
    }
}

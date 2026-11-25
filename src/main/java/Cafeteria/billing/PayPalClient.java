package Cafeteria.billing;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PayPalClient {

    public record Capture(String orderId, String status, String captureId, String captureStatus, String currency,
            String value, String customId, String payerEmail) {

        public boolean completed() {
            return "COMPLETED".equals(status) && "COMPLETED".equals(captureStatus);
        }
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final PaymentsProperties config;
    private final RestClient client;
    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public PayPalClient(PaymentsProperties config) {
        this.config = config;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(25));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public String createOrder(String reference, String description, int usdCents) {
        Map<String, Object> unit = Map.of(
                "reference_id", reference,
                "custom_id", reference,
                "description", description,
                "amount", Map.of("currency_code", "USD", "value", decimal(usdCents)));
        Map<String, Object> response = send(() -> client.post().uri(config.paypalBaseUrl() + "/v2/checkout/orders")
                .headers(headers -> {
                    headers.setBearerAuth(token());
                    headers.set("PayPal-Request-Id", "create-" + reference);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("intent", "CAPTURE", "purchase_units", List.of(unit)))
                .retrieve().body(MAP));
        return string(response.get("id"));
    }

    public Capture capture(String orderId, String reference) {
        try {
            Map<String, Object> response = send(() -> client.post()
                    .uri(config.paypalBaseUrl() + "/v2/checkout/orders/" + orderId + "/capture")
                    .headers(headers -> {
                        headers.setBearerAuth(token());
                        headers.set("PayPal-Request-Id", "capture-" + reference);
                    })
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of())
                    .retrieve().body(MAP));
            return parse(response);
        } catch (PaymentProviderException e) {
            if (e.status() == 422 && String.valueOf(e.getMessage()).contains("ORDER_ALREADY_CAPTURED")) {
                return get(orderId);
            }
            throw e;
        }
    }

    public Capture get(String orderId) {
        return parse(send(() -> client.get().uri(config.paypalBaseUrl() + "/v2/checkout/orders/" + orderId)
                .headers(headers -> headers.setBearerAuth(token())).retrieve().body(MAP)));
    }

    @SuppressWarnings("unchecked")
    static Capture parse(Map<String, Object> order) {
        List<Map<String, Object>> units = (List<Map<String, Object>>) order.getOrDefault("purchase_units", List.of());
        Map<String, Object> unit = units.isEmpty() ? Map.of() : units.get(0);
        Map<String, Object> payments = (Map<String, Object>) unit.getOrDefault("payments", Map.of());
        List<Map<String, Object>> captures = (List<Map<String, Object>>) payments.getOrDefault("captures", List.of());
        Map<String, Object> capture = captures.isEmpty() ? Map.of() : captures.get(0);
        Map<String, Object> amount = (Map<String, Object>) capture.getOrDefault("amount", Map.of());
        Map<String, Object> payer = (Map<String, Object>) order.getOrDefault("payer", Map.of());
        String customId = string(capture.get("custom_id"));
        return new Capture(string(order.get("id")), string(order.get("status")), string(capture.get("id")),
                string(capture.get("status")), string(amount.get("currency_code")), string(amount.get("value")),
                customId.isEmpty() ? string(unit.get("custom_id")) : customId, string(payer.get("email_address")));
    }

    private synchronized String token() {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiresAt.minus(REFRESH_MARGIN))) {
            return accessToken;
        }
        String basic = Base64.getEncoder().encodeToString(
                (config.paypalClientId() + ":" + config.paypalClientSecret()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        Map<String, Object> response = send(() -> client.post().uri(config.paypalBaseUrl() + "/v1/oauth2/token")
                .headers(headers -> headers.set("Authorization", "Basic " + basic))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(MAP));
        accessToken = string(response.get("access_token"));
        long seconds = response.get("expires_in") instanceof Number number ? number.longValue() : 300;
        accessTokenExpiresAt = Instant.now().plusSeconds(seconds);
        return accessToken;
    }

    private static Map<String, Object> send(java.util.function.Supplier<Map<String, Object>> call) {
        try {
            Map<String, Object> body = call.get();
            return body == null ? Map.of() : body;
        } catch (RestClientResponseException e) {
            throw new PaymentProviderException("PayPal respondió " + e.getStatusCode().value() + ": " + abbreviate(e.getResponseBodyAsString()),
                    e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            throw new PaymentProviderException("No se pudo contactar a PayPal", 0, e);
        }
    }

    static String decimal(int cents) {
        return String.format(java.util.Locale.ROOT, "%d.%02d", cents / 100, cents % 100);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String abbreviate(String text) {
        return text == null ? "" : text.length() > 240 ? text.substring(0, 240) : text;
    }
}

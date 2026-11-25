package Cafeteria.billing;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class MercadoPagoClient {

    public record Preference(String id, String initPoint) {}

    public record Payment(String id, String status, String statusDetail, BigDecimal amount, String currency,
            String externalReference, String payerEmail) {

        public boolean approved() {
            return "approved".equals(status);
        }
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final long SIGNATURE_TOLERANCE_SECONDS = 15 * 60;

    private final PaymentsProperties config;
    private final RestClient client;

    public MercadoPagoClient(PaymentsProperties config) {
        this.config = config;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(25));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public Preference createPreference(String reference, String planCode, String title, BigDecimal pesos, String payerEmail,
            Instant expiresAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(Map.of("id", planCode, "title", title, "quantity", 1, "currency_id", "ARS",
                "unit_price", pesos)));
        body.put("external_reference", reference);
        body.put("payer", Map.of("email", payerEmail));
        String back = config.resultUrl(reference);
        body.put("back_urls", Map.of("success", back, "failure", back, "pending", back));
        if (back.startsWith("https://")) {
            body.put("auto_return", "approved");
        }
        if (!config.mercadopagoNotificationUrl().isEmpty()) {
            body.put("notification_url", config.mercadopagoNotificationUrl());
        }
        body.put("statement_descriptor", "DELIORA");
        body.put("expires", true);
        body.put("expiration_date_to", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC)));
        Map<String, Object> response = send(() -> client.post().uri(config.mercadopagoBaseUrl() + "/checkout/preferences")
                .headers(headers -> headers.setBearerAuth(config.mercadopagoAccessToken()))
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP));
        return new Preference(string(response.get("id")), string(response.get("init_point")));
    }

    public Payment payment(String paymentId) {
        return parse(send(() -> client.get().uri(config.mercadopagoBaseUrl() + "/v1/payments/" + paymentId)
                .headers(headers -> headers.setBearerAuth(config.mercadopagoAccessToken())).retrieve().body(MAP)));
    }

    @SuppressWarnings("unchecked")
    public List<Payment> paymentsOf(String reference) {
        Map<String, Object> response = send(() -> client.get()
                .uri(config.mercadopagoBaseUrl() + "/v1/payments/search?sort=date_created&criteria=desc&external_reference={ref}", reference)
                .headers(headers -> headers.setBearerAuth(config.mercadopagoAccessToken())).retrieve().body(MAP));
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getOrDefault("results", List.of());
        List<Payment> payments = new ArrayList<>();
        for (Map<String, Object> result : results) {
            payments.add(parse(result));
        }
        return payments;
    }

    @SuppressWarnings("unchecked")
    static Payment parse(Map<String, Object> payment) {
        Map<String, Object> payer = (Map<String, Object>) payment.getOrDefault("payer", Map.of());
        Object amount = payment.get("transaction_amount");
        return new Payment(string(payment.get("id")), string(payment.get("status")), string(payment.get("status_detail")),
                amount == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(amount)), string(payment.get("currency_id")),
                string(payment.get("external_reference")), string(payer.get("email")));
    }

    public boolean validSignature(String signatureHeader, String requestId, String dataId, Instant now) {
        String secret = config.mercadopagoWebhookSecret();
        if (secret.isEmpty() || signatureHeader == null || dataId == null) {
            return false;
        }
        String ts = null;
        String received = null;
        for (String part : signatureHeader.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && "ts".equals(pair[0])) {
                ts = pair[1];
            } else if (pair.length == 2 && "v1".equals(pair[0])) {
                received = pair[1];
            }
        }
        if (ts == null || received == null) {
            return false;
        }
        try {
            long seconds = Long.parseLong(ts) > 99_999_999_999L ? Long.parseLong(ts) / 1000 : Long.parseLong(ts);
            if (Math.abs(now.getEpochSecond() - seconds) > SIGNATURE_TOLERANCE_SECONDS) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        String id = dataId.matches("[A-Za-z0-9]+") ? dataId.toLowerCase() : dataId;
        String manifest = "id:" + id + ";request-id:" + (requestId == null ? "" : requestId) + ";ts:" + ts + ";";
        return MessageDigest.isEqual(hmac(secret, manifest).getBytes(StandardCharsets.UTF_8), received.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> send(Supplier<Map<String, Object>> call) {
        try {
            Map<String, Object> body = call.get();
            return body == null ? Map.of() : body;
        } catch (RestClientResponseException e) {
            throw new PaymentProviderException("Mercado Pago respondió " + e.getStatusCode().value() + ": " + abbreviate(e.getResponseBodyAsString()),
                    e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            throw new PaymentProviderException("No se pudo contactar a Mercado Pago", 0, e);
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String abbreviate(String text) {
        return text == null ? "" : text.length() > 240 ? text.substring(0, 240) : text;
    }
}

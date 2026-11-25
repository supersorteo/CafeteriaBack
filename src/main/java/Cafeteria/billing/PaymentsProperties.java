package Cafeteria.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentsProperties {

    private final boolean mercadopagoEnabled;
    private final String mercadopagoBaseUrl;
    private final String mercadopagoAccessToken;
    private final String mercadopagoPublicKey;
    private final String mercadopagoWebhookSecret;
    private final String mercadopagoNotificationUrl;
    private final boolean paypalEnabled;
    private final String paypalBaseUrl;
    private final String paypalClientId;
    private final String paypalClientSecret;
    private final int checkoutMinutes;
    private final String publicBaseUrl;

    public PaymentsProperties(
            @Value("${mercadopago.enabled:true}") boolean mercadopagoEnabled,
            @Value("${mercadopago.base-url:https://api.mercadopago.com}") String mercadopagoBaseUrl,
            @Value("${mercadopago.access-token:}") String mercadopagoAccessToken,
            @Value("${mercadopago.public-key:}") String mercadopagoPublicKey,
            @Value("${mercadopago.webhook-secret:}") String mercadopagoWebhookSecret,
            @Value("${mercadopago.notification-url:}") String mercadopagoNotificationUrl,
            @Value("${paypal.enabled:true}") boolean paypalEnabled,
            @Value("${paypal.base-url:https://api-m.sandbox.paypal.com}") String paypalBaseUrl,
            @Value("${paypal.client-id:}") String paypalClientId,
            @Value("${paypal.client-secret:}") String paypalClientSecret,
            @Value("${billing.checkout-minutes:60}") int checkoutMinutes,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.mercadopagoEnabled = mercadopagoEnabled;
        this.mercadopagoBaseUrl = trimSlash(mercadopagoBaseUrl);
        this.mercadopagoAccessToken = mercadopagoAccessToken.strip();
        this.mercadopagoPublicKey = mercadopagoPublicKey.strip();
        this.mercadopagoWebhookSecret = mercadopagoWebhookSecret.strip();
        this.mercadopagoNotificationUrl = mercadopagoNotificationUrl.strip();
        this.paypalEnabled = paypalEnabled;
        this.paypalBaseUrl = trimSlash(paypalBaseUrl);
        this.paypalClientId = paypalClientId.strip();
        this.paypalClientSecret = paypalClientSecret.strip();
        this.checkoutMinutes = Math.max(10, checkoutMinutes);
        this.publicBaseUrl = trimSlash(publicBaseUrl);
    }

    public boolean mercadopagoConfigured() {
        return mercadopagoEnabled && !mercadopagoAccessToken.isEmpty();
    }

    public boolean paypalConfigured() {
        return paypalEnabled && !paypalClientId.isEmpty() && !paypalClientSecret.isEmpty();
    }

    public String mercadopagoBaseUrl() {
        return mercadopagoBaseUrl;
    }

    public String mercadopagoAccessToken() {
        return mercadopagoAccessToken;
    }

    public String mercadopagoPublicKey() {
        return mercadopagoPublicKey;
    }

    public String mercadopagoWebhookSecret() {
        return mercadopagoWebhookSecret;
    }

    public String mercadopagoNotificationUrl() {
        return mercadopagoNotificationUrl;
    }

    public String paypalBaseUrl() {
        return paypalBaseUrl;
    }

    public String paypalClientId() {
        return paypalClientId;
    }

    public String paypalClientSecret() {
        return paypalClientSecret;
    }

    public int checkoutMinutes() {
        return checkoutMinutes;
    }

    public String resultUrl(String reference) {
        return publicBaseUrl + "/pago/resultado?ref=" + reference;
    }

    private static String trimSlash(String url) {
        String clean = url == null ? "" : url.strip();
        return clean.endsWith("/") ? clean.substring(0, clean.length() - 1) : clean;
    }
}

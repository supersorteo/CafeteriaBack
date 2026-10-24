package Cafeteria.mail;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

@Component
@ConditionalOnProperty(name = "mail.sender", havingValue = "gmail-api")
public class GmailApiEmailSender implements EmailSender {

    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final RestClient client;
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private final String sender;
    private final String tokenUrl;
    private final String apiUrl;

    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public GmailApiEmailSender(
            @Value("${mail.gmail.client-id:}") String clientId,
            @Value("${mail.gmail.client-secret:}") String clientSecret,
            @Value("${mail.gmail.refresh-token:}") String refreshToken,
            @Value("${mail.gmail.sender:${mail.from:}}") String sender,
            @Value("${mail.gmail.token-url:https://oauth2.googleapis.com/token}") String tokenUrl,
            @Value("${mail.gmail.api-url:https://gmail.googleapis.com/gmail/v1/users/me/messages/send}") String apiUrl) {
        requireSet("mail.gmail.client-id (GMAIL_CLIENT_ID)", clientId);
        requireSet("mail.gmail.client-secret (GMAIL_CLIENT_SECRET)", clientSecret);
        requireSet("mail.gmail.refresh-token (GMAIL_REFRESH_TOKEN)", refreshToken);
        requireSet("mail.gmail.sender (MAIL_FROM)", sender);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
        this.sender = sender;
        this.tokenUrl = tokenUrl;
        this.apiUrl = apiUrl;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void send(EmailMessage message) {
        String raw = encode(message);
        try {
            post(raw, token(false));
        } catch (HttpClientErrorException.Unauthorized e) {
            post(raw, token(true));
        } catch (RestClientException e) {
            throw failure("el envío", e);
        }
    }

    private void post(String raw, String token) {
        try {
            client.post().uri(apiUrl)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("raw", raw))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw e;
        } catch (RestClientException e) {
            throw failure("el envío", e);
        }
    }

    private synchronized String token(boolean force) {
        if (!force && accessToken != null && Instant.now().isBefore(accessTokenExpiresAt.minus(REFRESH_MARGIN))) {
            return accessToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = client.post().uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            Object token = body == null ? null : body.get("access_token");
            if (!(token instanceof String value) || value.isBlank()) {
                throw new IllegalStateException("Gmail API: la respuesta de autenticación no incluye access_token");
            }
            long seconds = body.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
            accessToken = value;
            accessTokenExpiresAt = Instant.now().plusSeconds(seconds);
            return accessToken;
        } catch (RestClientException e) {
            throw failure("la renovación del token", e);
        }
    }

    private String encode(EmailMessage message) {
        try {
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
            mime.setFrom(new InternetAddress(sender, true));
            mime.setRecipients(RecipientType.TO, InternetAddress.parse(message.to(), true));
            mime.setSubject(message.subject(), "UTF-8");
            mime.setText(message.body(), "UTF-8");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            mime.writeTo(out);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalArgumentException("No se pudo construir el correo: " + e.getClass().getSimpleName(), e);
        }
    }

    private static IllegalStateException failure(String step, RestClientException e) {
        String status = e instanceof org.springframework.web.client.RestClientResponseException r
                ? " (HTTP " + r.getStatusCode().value() + ")" : "";
        return new IllegalStateException("Gmail API: falló " + step + status + " [" + e.getClass().getSimpleName() + "]");
    }

    private static void requireSet(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta configurar " + name + " para mail.sender=gmail-api");
        }
    }
}

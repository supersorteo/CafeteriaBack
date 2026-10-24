package Cafeteria.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

class GmailApiEmailSenderTest {

    private HttpServer google;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger sendCalls = new AtomicInteger();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> rawMessages = new CopyOnWriteArrayList<>();
    private final List<String> tokenForms = new CopyOnWriteArrayList<>();
    private volatile int expiresIn = 3600;
    private volatile int tokenStatus = 200;
    private volatile Function<String, Integer> sendStatus = auth -> 200;

    @BeforeEach
    void startFakeGoogle() throws IOException {
        google = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        google.createContext("/token", exchange -> {
            int call = tokenCalls.incrementAndGet();
            tokenForms.add(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            if (tokenStatus != 200) {
                reply(exchange, tokenStatus, "{\"error\":\"invalid_grant\",\"detail\":\"secreto-que-no-debe-aparecer\"}");
                return;
            }
            reply(exchange, 200, "{\"access_token\":\"tok-" + call + "\",\"expires_in\":" + expiresIn + ",\"token_type\":\"Bearer\"}");
        });
        google.createContext("/send", exchange -> {
            sendCalls.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(auth);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = sendStatus.apply(auth);
            if (status == 200) {
                Matcher matcher = Pattern.compile("\"raw\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                assertThat(matcher.find()).isTrue();
                rawMessages.add(matcher.group(1));
            }
            reply(exchange, status, status == 200 ? "{\"id\":\"m1\"}" : "{\"error\":\"x\"}");
        });
        google.start();
    }

    @AfterEach
    void stopFakeGoogle() {
        google.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private GmailApiEmailSender sender() {
        String base = "http://127.0.0.1:" + google.getAddress().getPort();
        return new GmailApiEmailSender("client-id", "client-secret", "refresh-token", "negocio@gmail.com", base + "/token", base + "/send");
    }

    private MimeMessage lastMime() throws Exception {
        byte[] bytes = Base64.getUrlDecoder().decode(rawMessages.get(rawMessages.size() - 1));
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes));
    }

    @Test
    void enviaElCorreoConRemitenteDestinatarioAsuntoYCuerpoEnUtf8() throws Exception {
        sender().send(new EmailMessage("cliente@cafe.test", "Confirma tu correo ñ", "Hola, confirma aquí: http://x/auth/verify-email?token=abc\nGracias — ¡salud!"));

        MimeMessage mime = lastMime();
        assertThat(mime.getFrom()[0].toString()).isEqualTo("negocio@gmail.com");
        assertThat(mime.getRecipients(RecipientType.TO)[0].toString()).isEqualTo("cliente@cafe.test");
        assertThat(mime.getSubject()).isEqualTo("Confirma tu correo ñ");
        assertThat(mime.getContent().toString()).contains("verify-email?token=abc").contains("¡salud!");
        assertThat(authorizations).containsExactly("Bearer tok-1");
    }

    @Test
    void pideElTokenConElRefreshTokenYLasCredencialesConfiguradas() {
        sender().send(new EmailMessage("a@cafe.test", "x", "y"));

        assertThat(tokenForms.get(0)).contains("grant_type=refresh_token").contains("refresh_token=refresh-token")
                .contains("client_id=client-id").contains("client_secret=client-secret");
    }

    @Test
    void reutilizaElTokenMientrasSigaVigente() {
        GmailApiEmailSender sender = sender();
        sender.send(new EmailMessage("a@cafe.test", "uno", "1"));
        sender.send(new EmailMessage("b@cafe.test", "dos", "2"));

        assertThat(tokenCalls).hasValue(1);
        assertThat(sendCalls).hasValue(2);
        assertThat(authorizations).containsExactly("Bearer tok-1", "Bearer tok-1");
    }

    @Test
    void renuevaElTokenCuandoEstaPorCaducar() {
        expiresIn = 30;
        GmailApiEmailSender sender = sender();
        sender.send(new EmailMessage("a@cafe.test", "uno", "1"));
        sender.send(new EmailMessage("b@cafe.test", "dos", "2"));

        assertThat(tokenCalls).hasValue(2);
        assertThat(authorizations).containsExactly("Bearer tok-1", "Bearer tok-2");
    }

    @Test
    void siGoogleRechazaElTokenLoRenuevaYReintentaUnaVez() {
        sendStatus = auth -> "Bearer tok-1".equals(auth) ? 401 : 200;

        sender().send(new EmailMessage("a@cafe.test", "x", "y"));

        assertThat(tokenCalls).hasValue(2);
        assertThat(authorizations).containsExactly("Bearer tok-1", "Bearer tok-2");
        assertThat(rawMessages).hasSize(1);
    }

    @Test
    void unRechazoPersistenteFallaSinReintentarIndefinidamente() {
        sendStatus = auth -> 401;

        assertThatThrownBy(() -> sender().send(new EmailMessage("a@cafe.test", "x", "y")))
                .isInstanceOf(RuntimeException.class);

        assertThat(sendCalls.get()).isEqualTo(2);
    }

    @Test
    void unErrorDelServidorDeGoogleSeReportaSinFiltrarSecretos() {
        sendStatus = auth -> 500;

        assertThatThrownBy(() -> sender().send(new EmailMessage("a@cafe.test", "x", "y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageNotContaining("refresh-token")
                .hasMessageNotContaining("client-secret")
                .hasMessageNotContaining("tok-1");
    }

    @Test
    void siNoSePuedeRenovarElTokenFallaSinEnviarNiExponerLaRespuesta() {
        tokenStatus = 400;

        assertThatThrownBy(() -> sender().send(new EmailMessage("a@cafe.test", "x", "y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("renovación del token")
                .hasMessageNotContaining("secreto-que-no-debe-aparecer");

        assertThat(sendCalls).hasValue(0);
    }

    @Test
    void rechazaUnDestinatarioConInyeccionDeCabecerasSinLlamarAGoogle() {
        assertThatThrownBy(() -> sender().send(new EmailMessage("victima@cafe.test\nBcc: otro@x.test", "x", "y")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(tokenCalls).hasValue(0);
        assertThat(sendCalls).hasValue(0);
    }

    @Test
    void fallaAlArrancarSiFaltaConfiguracion() {
        assertThatThrownBy(() -> new GmailApiEmailSender("", "s", "r", "a@gmail.com", "http://x", "http://y"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GMAIL_CLIENT_ID");
        assertThatThrownBy(() -> new GmailApiEmailSender("c", "s", "", "a@gmail.com", "http://x", "http://y"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GMAIL_REFRESH_TOKEN");
        assertThatThrownBy(() -> new GmailApiEmailSender("c", "s", "r", " ", "http://x", "http://y"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_FROM");
    }
}

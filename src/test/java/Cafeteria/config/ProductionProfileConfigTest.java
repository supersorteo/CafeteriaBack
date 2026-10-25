package Cafeteria.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ProductionProfileConfigTest {

    private static final Properties PROD = new Properties();
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z_]+(:.*)?}$");

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = new ClassPathResource("application-production.properties").getInputStream()) {
            PROD.load(in);
        }
    }

    private static String get(String key) {
        return PROD.getProperty(key);
    }

    @Test
    void losSecretosSoloLlegaranPorVariablesDeEntorno() {
        for (String key : new String[] { "spring.datasource.password", "cloudinary.api-secret", "cloudinary.api-key",
                "mail.gmail.client-secret", "mail.gmail.refresh-token", "spring.mail.password" }) {
            assertThat(get(key)).as(key).matches(PLACEHOLDER);
        }
    }

    @Test
    void ningunValorDelArchivoContieneCredencialesLiterales() {
        PROD.forEach((key, value) -> {
            String name = key.toString().toLowerCase();
            if (name.contains("password") || name.contains("secret") || name.contains("token") || name.contains("api-key")) {
                assertThat(value.toString()).as(name).matches(PLACEHOLDER);
            }
        });
    }

    @Test
    void laBaseDeDatosAdmiteLasVariablesDeRailway() {
        assertThat(get("spring.datasource.url")).isEqualTo("${DB_URL}");
        assertThat(get("spring.datasource.username")).isEqualTo("${DB_USER}");
        assertThat(get("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
        assertThat(get("server.port")).isEqualTo("${PORT:8080}");
    }

    @Test
    void esSeguroPorDefecto() {
        assertThat(get("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(get("spring.jpa.show-sql")).isEqualTo("false");
        assertThat(get("seed.dev.enabled")).isEqualTo("false");
        assertThat(get("registration.enabled")).isEqualTo("${REGISTRATION_ENABLED:false}");
        assertThat(get("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(get("server.servlet.session.cookie.http-only")).isEqualTo("true");
        assertThat(get("server.servlet.session.cookie.same-site")).isEqualTo("lax");
        assertThat(get("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(get("management.endpoint.health.show-details")).isEqualTo("never");
    }

    @Test
    void laSaludNoDependeDelCorreoExterno() {
        assertThat(get("management.health.mail.enabled")).isEqualTo("false");
    }

    @Test
    void confiaEnLosProxiesYUsaCloudinaryYSmtpPorDefecto() {
        assertThat(get("server.forward-headers-strategy")).isEqualTo("framework");
        assertThat(get("security.trust-forwarded-for")).isEqualTo("true");
        assertThat(get("media.storage")).isEqualTo("cloudinary");
        assertThat(get("mail.sender")).isEqualTo("${MAIL_SENDER:smtp}");
    }

    @Test
    void esAutosuficienteSinElApplicationPropertiesLocal() {
        for (String key : new String[] { "spring.application.name", "spring.flyway.enabled", "spring.jpa.open-in-view",
                "spring.session.jdbc.initialize-schema", "spring.servlet.multipart.max-file-size", "app.public-base-url",
                "outbox.worker.enabled", "media.cleanup.enabled", "mail.from" }) {
            assertThat(get(key)).as(key).isNotBlank();
        }
        assertThat(get("spring.jpa.open-in-view")).isEqualTo("false");
    }
}

package Cafeteria.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.FileSystemUtils;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import Cafeteria.auth.User;
import Cafeteria.auth.UserRepository;
import Cafeteria.mail.OutboxProcessor;
import Cafeteria.media.MediaAsset;
import Cafeteria.media.MediaService;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;
import Cafeteria.tenant.TenantUser;
import Cafeteria.tenant.TenantUserRepository;
import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTestSupport.FlywayCleanConfig.class)
public abstract class IntegrationTestSupport {

    public static final String PASSWORD = "Test-Password-1!";

    @TestConfiguration
    static class FlywayCleanConfig {
        @Bean
        CapturingEmailSender capturingEmailSender() {
            return new CapturingEmailSender();
        }

        @Bean
        FlywayMigrationStrategy cleanMigrate() {
            return flyway -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected UserRepository users;
    @Autowired
    protected TenantRepository tenants;
    @Autowired
    protected TenantUserRepository memberships;
    @Autowired
    protected PasswordEncoder encoder;
    @Autowired
    protected CapturingEmailSender mail;
    @Autowired
    protected OutboxProcessor outboxProcessor;
    @Autowired
    protected MediaService mediaService;
    @Value("${app.upload.dir}")
    String uploadDir;

    protected Path uploadRoot() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }

    protected MediaAsset uploadDirect(Tenant tenant) {
        return mediaService.upload(tenant.getId(), TestImages.jpeg(32, 24));
    }

    protected ResultActions uploadFile(Cookie session, String filename, String contentType, byte[] content)
            throws Exception {
        return mvc.perform(withXsrf(multipart("/api/v1/admin/media")
                .file(new MockMultipartFile("file", filename, contentType, content)).cookie(session), newXsrf()));
    }

    @BeforeEach
    void cleanDatabase() throws Exception {
        FileSystemUtils.deleteRecursively(uploadRoot());
        mail.reset();
        jdbc.execute("TRUNCATE site_images, menu_items, categories, media_assets, tenant_users, account_tokens, "
                + "email_outbox, audit_log, tenants, users CASCADE");
        jdbc.execute("TRUNCATE spring_session CASCADE");
    }

    protected Tenant tenant(String slug) {
        return tenants.save(new Tenant(slug, "Negocio " + slug));
    }

    protected User member(String email, Tenant tenant, Role role, boolean verified) {
        User user = new User(email, encoder.encode(PASSWORD));
        if (verified) {
            user.setEmailVerifiedAt(Instant.now());
        }
        users.save(user);
        memberships.save(new TenantUser(user.getId(), tenant.getId(), role));
        return user;
    }

    protected Cookie newXsrf() throws Exception {
        return mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    protected <B extends AbstractMockHttpServletRequestBuilder<B>> B withXsrf(B request, Cookie xsrf) {
        return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
    }

    protected MockHttpServletRequestBuilder loginRequest(String email, String password) throws Exception {
        return withXsrf(post("/api/v1/auth/login"), newXsrf()).param("username", email).param("password", password);
    }

    protected ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(withXsrf(post(path), newXsrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected Cookie login(String email) throws Exception {
        return mvc.perform(loginRequest(email, PASSWORD))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("SESSION"))
                .andReturn().getResponse().getCookie("SESSION");
    }
}

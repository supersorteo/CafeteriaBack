package Cafeteria.auth;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import Cafeteria.support.IntegrationTestSupport;

@TestPropertySource(properties = { "security.register.max-attempts=2", "security.public.max-attempts=2" })
class RegisterRateLimitTest extends IntegrationTestSupport {

    @Test
    void registroYRecuperacionSeLimitanPorIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            postJson("/api/v1/auth/register",
                    "{\"email\":\"a%d@cafe.test\",\"password\":\"Password-12345\",\"businessName\":\"X\",\"slug\":\"cafe-%d\"}"
                            .formatted(i, i)).andExpect(status().isAccepted());
        }
        postJson("/api/v1/auth/register",
                "{\"email\":\"a9@cafe.test\",\"password\":\"Password-12345\",\"businessName\":\"X\",\"slug\":\"cafe-9\"}")
                .andExpect(status().isTooManyRequests());

        for (int i = 0; i < 2; i++) {
            postJson("/api/v1/auth/forgot-password", "{\"email\":\"x@cafe.test\"}").andExpect(status().isAccepted());
        }
        postJson("/api/v1/auth/forgot-password", "{\"email\":\"x@cafe.test\"}").andExpect(status().isTooManyRequests());
    }
}

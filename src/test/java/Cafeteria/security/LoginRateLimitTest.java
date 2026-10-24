package Cafeteria.security;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import Cafeteria.support.IntegrationTestSupport;

@TestPropertySource(properties = "security.login.max-attempts=3")
class LoginRateLimitTest extends IntegrationTestSupport {

    @Test
    void tercerIntentoPasaYElCuartoSeBloquea() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(loginRequest("x@a.test", "mala")).andExpect(status().isUnauthorized());
        }
        mvc.perform(loginRequest("x@a.test", "mala"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}

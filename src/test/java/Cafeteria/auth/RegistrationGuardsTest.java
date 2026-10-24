package Cafeteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import Cafeteria.support.IntegrationTestSupport;

@TestPropertySource(properties = { "registration.enabled=false" })
class RegistrationGuardsTest extends IntegrationTestSupport {

    @Test
    void conRegistroDeshabilitadoElBackendRechazaSinCrearDatos() throws Exception {
        postJson("/api/v1/auth/register",
                "{\"email\":\"a@cafe.test\",\"password\":\"Password-12345\",\"businessName\":\"X\",\"slug\":\"cafe-ok\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("registration_disabled"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants", Long.class)).isZero();
    }
}

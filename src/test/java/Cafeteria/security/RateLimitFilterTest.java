package Cafeteria.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServletRequest;

class RateLimitFilterTest {

    static final String PATH = "/api/v1/auth/login";

    private static MockHttpServletRequest post(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setRequestURI(PATH);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private static int status(RateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() != null ? 200 : response.getStatus();
    }

    private static Function<HttpServletRequest, String> key(boolean trust) {
        return request -> ClientIp.resolve(request, trust);
    }

    @Test
    void limitaPorIpDelClienteLeyendoXForwardedForCuandoSeConfiaEnElProxy() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(PATH, 2, Duration.ofMinutes(5), key(true), 1000);

        assertThat(status(filter, post("10.0.0.1", "203.0.113.5, 10.0.0.1"))).isEqualTo(200);
        assertThat(status(filter, post("10.0.0.1", "203.0.113.5"))).isEqualTo(200);
        assertThat(status(filter, post("10.0.0.1", "203.0.113.5"))).isEqualTo(429);

        assertThat(status(filter, post("10.0.0.1", "203.0.113.99"))).as("otro cliente detrás del mismo proxy").isEqualTo(200);
    }

    @Test
    void sinConfianzaIgnoraLaCabeceraYLimitaPorLaDireccionRemota() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(PATH, 2, Duration.ofMinutes(5), key(false), 1000);

        assertThat(status(filter, post("198.51.100.7", "1.1.1.1"))).isEqualTo(200);
        assertThat(status(filter, post("198.51.100.7", "2.2.2.2"))).isEqualTo(200);
        assertThat(status(filter, post("198.51.100.7", "3.3.3.3"))).as("falsificar la cabecera no sirve").isEqualTo(429);
    }

    @Test
    void unaCabeceraConBasuraSeIgnoraYSeUsaLaDireccionRemota() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(PATH, 1, Duration.ofMinutes(5), key(true), 1000);

        assertThat(status(filter, post("198.51.100.7", "<script>alert(1)</script>"))).isEqualTo(200);
        assertThat(status(filter, post("198.51.100.7", "otra-basura"))).isEqualTo(429);
    }

    @Test
    void elTechoGlobalFrenaUnaRotacionMasivaDeIpsFalsas() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(PATH, 2, Duration.ofMinutes(5), key(true), 5);

        int accepted = 0;
        for (int i = 1; i <= 20; i++) {
            if (status(filter, post("10.0.0.1", "203.0.113." + i)) == 200) {
                accepted++;
            }
        }

        assertThat(accepted).as("solo pasan hasta el techo global").isEqualTo(5);
        assertThat(status(filter, post("10.0.0.1", "203.0.113.200"))).isEqualTo(429);
    }

    @Test
    void laVentanaSeLiberaConElTiempo() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(PATH, 1, Duration.ofMillis(80), key(true), 1000);

        assertThat(status(filter, post("10.0.0.1", "203.0.113.5"))).isEqualTo(200);
        assertThat(status(filter, post("10.0.0.1", "203.0.113.5"))).isEqualTo(429);
        Thread.sleep(150);
        assertThat(status(filter, post("10.0.0.1", "203.0.113.5"))).isEqualTo(200);
    }

    @Test
    void soloAplicaALaRutaYMetodoConfigurados() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(PATH, 1, Duration.ofMinutes(5), key(true), 1000);
        MockHttpServletRequest get = new MockHttpServletRequest("GET", PATH);
        get.setRequestURI(PATH);
        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/v1/otra");
        other.setRequestURI("/api/v1/otra");

        for (int i = 0; i < 5; i++) {
            assertThat(status(filter, get)).isEqualTo(200);
            assertThat(status(filter, other)).isEqualTo(200);
        }
    }

    @Test
    void respondeConProblemJsonYRetryAfter() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(PATH, 1, Duration.ofMinutes(15), key(true), 1000);
        status(filter, post("10.0.0.1", "203.0.113.5"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(post("10.0.0.1", "203.0.113.5"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("900");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("too_many_attempts");
    }
}

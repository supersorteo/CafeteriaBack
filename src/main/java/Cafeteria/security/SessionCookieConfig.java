package Cafeteria.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import jakarta.servlet.http.HttpServletRequest;

@Configuration
public class SessionCookieConfig {

    public static final String TENANT_COOKIE = "SESSION";
    public static final String SUPERADMIN_COOKIE = "SA_SESSION";
    public static final String SUPERADMIN_PATH = "/api/v1/superadmin";

    @Bean
    CookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.secure:#{null}}") Boolean secure,
            @Value("${server.servlet.session.cookie.same-site:lax}") String sameSite) {
        DefaultCookieSerializer tenant = new DefaultCookieSerializer();
        tenant.setCookieName(TENANT_COOKIE);
        tenant.setUseHttpOnlyCookie(true);
        tenant.setSameSite(sameSite);

        DefaultCookieSerializer superAdmin = new DefaultCookieSerializer();
        superAdmin.setCookieName(SUPERADMIN_COOKIE);
        superAdmin.setCookiePath(SUPERADMIN_PATH);
        superAdmin.setUseHttpOnlyCookie(true);
        superAdmin.setSameSite("Strict");

        if (secure != null) {
            tenant.setUseSecureCookie(secure);
            superAdmin.setUseSecureCookie(secure);
        }
        return new CookieSerializer() {
            @Override
            public void writeCookieValue(CookieValue cookieValue) {
                route(cookieValue.getRequest()).writeCookieValue(cookieValue);
            }

            @Override
            public List<String> readCookieValues(HttpServletRequest request) {
                return route(request).readCookieValues(request);
            }

            private DefaultCookieSerializer route(HttpServletRequest request) {
                String uri = request.getRequestURI();
                return uri != null && (uri.equals(SUPERADMIN_PATH) || uri.startsWith(SUPERADMIN_PATH + "/"))
                        ? superAdmin : tenant;
            }
        };
    }
}

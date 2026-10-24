package Cafeteria.security;

import java.time.Duration;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfException;

import Cafeteria.shared.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;

@Configuration
public class SecurityConfig {

    static final String API = "/api/v1";
    static final String LOGIN_PATH = API + "/auth/login";
    static final String[] PUBLIC_POSTS = { API + "/auth/register", API + "/auth/verify-email",
            API + "/auth/resend-verification", API + "/auth/forgot-password", API + "/auth/reset-password" };

    private static int globalCeiling(int perClient, int factor) {
        return (int) Math.min(Integer.MAX_VALUE, (long) perClient * Math.max(1, factor));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${security.login.max-attempts:10}") int maxAttempts,
            @Value("${security.login.window-minutes:15}") int windowMinutes,
            @Value("${security.public.max-attempts:20}") int publicMaxAttempts,
            @Value("${security.register.max-attempts:5}") int registerMaxAttempts,
            @Value("${security.register.window-minutes:60}") int registerWindowMinutes,
            @Value("${security.trust-forwarded-for:false}") boolean trustForwardedFor,
            @Value("${security.global-limit-factor:10}") int globalLimitFactor) throws Exception {
        Function<HttpServletRequest, String> clientKey = request -> ClientIp.resolve(request, trustForwardedFor);
        http
                .csrf(csrf -> csrf.spa())
                .cors(cors -> cors.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(API + "/auth/csrf", API + "/public/**", "/media/**",
                                "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll()
                        .requestMatchers(API + "/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginProcessingUrl(LOGIN_PATH)
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler((request, response, authentication) -> {
                            response.setStatus(HttpStatus.OK.value());
                            response.setContentType("application/json");
                            response.getWriter().write("{\"authenticated\":true}");
                        })
                        .failureHandler((request, response, exception) -> ProblemResponses.write(response,
                                HttpStatus.UNAUTHORIZED, "invalid_credentials", "Credenciales inválidas")))
                .logout(logout -> logout
                        .logoutUrl(API + "/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, exception) -> ProblemResponses.write(response,
                                HttpStatus.UNAUTHORIZED, "unauthenticated", "Sesión requerida"))
                        .accessDeniedHandler((request, response, exception) -> ProblemResponses.write(response,
                                HttpStatus.FORBIDDEN,
                                exception instanceof CsrfException ? "csrf_invalid" : "forbidden",
                                exception instanceof CsrfException ? "Token CSRF ausente o inválido"
                                        : "Operación no permitida")))
                .addFilterBefore(new RateLimitFilter(LOGIN_PATH, maxAttempts, Duration.ofMinutes(windowMinutes), clientKey,
                        globalCeiling(maxAttempts, globalLimitFactor)), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new RateLimitFilter(API + "/auth/register", registerMaxAttempts,
                        Duration.ofMinutes(registerWindowMinutes), clientKey,
                        globalCeiling(registerMaxAttempts, globalLimitFactor)), UsernamePasswordAuthenticationFilter.class);
        for (String path : PUBLIC_POSTS) {
            if (!path.endsWith("/register")) {
                http.addFilterBefore(new RateLimitFilter(path, publicMaxAttempts, Duration.ofMinutes(windowMinutes), clientKey,
                        globalCeiling(publicMaxAttempts, globalLimitFactor)), UsernamePasswordAuthenticationFilter.class);
            }
        }
        return http.build();
    }
}

package Cafeteria.shared;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;

import jakarta.servlet.http.HttpServletResponse;

public final class ProblemResponses {

    private ProblemResponses() {}

    public static void write(HttpServletResponse response, HttpStatus status, String code, String title)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status.value()
                + ",\"code\":\"" + code + "\""
                + ",\"title\":\"" + title.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }
}

package Cafeteria.security;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIp {

    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private ClientIp() {}

    public static String resolve(HttpServletRequest request, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String header = request.getHeader("X-Forwarded-For");
            if (header != null) {
                String first = header.split(",")[0].strip();
                if (IP_LITERAL.matcher(first).matches()) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }
}

package Cafeteria.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailTemplates {

    public static final String VERIFY_EMAIL = "VERIFY_EMAIL";
    public static final String RESET_PASSWORD = "RESET_PASSWORD";
    public static final String ACCOUNT_EXISTS = "ACCOUNT_EXISTS";

    private final String baseUrl;

    public EmailTemplates(@Value("${app.public-base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public EmailMessage build(String type, String recipient, String payload) {
        return switch (type) {
            case VERIFY_EMAIL -> new EmailMessage(recipient, "Confirma tu correo",
                    "Gracias por registrarte. Confirma tu correo para activar la cuenta (válido 24 horas):\n\n"
                            + baseUrl + "/auth/verify-email?token=" + payload
                            + "\n\nSi no creaste esta cuenta, ignora este mensaje.");
            case RESET_PASSWORD -> new EmailMessage(recipient, "Restablece tu contraseña",
                    "Recibimos una solicitud para restablecer tu contraseña (enlace válido 1 hora):\n\n"
                            + baseUrl + "/auth/reset-password?token=" + payload
                            + "\n\nSi no fuiste tú, ignora este mensaje.");
            case ACCOUNT_EXISTS -> new EmailMessage(recipient, "Ya tienes una cuenta",
                    "Alguien intentó registrar este correo, pero ya tiene una cuenta.\n\n"
                            + "Si fuiste tú, inicia sesión o recupera tu contraseña en " + baseUrl + "/auth/forgot-password"
                            + "\n\nSi no fuiste tú, ignora este mensaje.");
            default -> throw new IllegalArgumentException("Tipo de correo desconocido: " + type);
        };
    }
}

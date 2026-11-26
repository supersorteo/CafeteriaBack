package Cafeteria.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailTemplates {

    public static final String VERIFY_EMAIL = "VERIFY_EMAIL";
    public static final String RESET_PASSWORD = "RESET_PASSWORD";
    public static final String ACCOUNT_EXISTS = "ACCOUNT_EXISTS";
    public static final String ACTIVATION_CODE = "ACTIVATION_CODE";
    public static final String PAYMENT_RECEIPT = "PAYMENT_RECEIPT";
    public static final String EMAIL_CODE = "EMAIL_CODE";

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
            case ACTIVATION_CODE -> new EmailMessage(recipient, "Tu código de activación",
                    "Recibimos tu pago. Tu código de activación es:\n\n" + payload
                            + "\n\nCrea tu negocio aquí (el código ya viene escrito):\n"
                            + baseUrl + "/auth/register?code=" + payload + "&email="
                            + java.net.URLEncoder.encode(recipient, java.nio.charset.StandardCharsets.UTF_8)
                            + "\n\nEl plazo de tu plan empieza cuando lo uses. Si no esperabas este mensaje, ignóralo.");
            case EMAIL_CODE -> new EmailMessage(recipient, "Tu código de verificación",
                    "Tu código para continuar con tu compra en Deliora es:\n\n" + payload
                            + "\n\nVale 15 minutos. Si no fuiste tú, ignora este mensaje.");
            case PAYMENT_RECEIPT -> {
                String[] parts = payload.split("\\|", 4);
                yield new EmailMessage(recipient, "Recibo de tu pago en Deliora",
                        "Recibimos tu pago de " + parts[2] + " por el plan " + parts[1] + ". Gracias por confiar en Deliora.\n\n"
                                + "Tu negocio ya está creado: " + baseUrl + "/" + parts[0] + "\n"
                                + "Entra a tu panel con tu correo y la contraseña que elegiste: " + baseUrl + "/auth/login");
            }
            default -> throw new IllegalArgumentException("Tipo de correo desconocido: " + type);
        };
    }
}

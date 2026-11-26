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
    public static final String PLAN_NOTICE = "PLAN_NOTICE";
    public static final String RENEWAL_RECEIPT = "RENEWAL_RECEIPT";

    private static final java.time.format.DateTimeFormatter DATE = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
            .withZone(java.time.ZoneId.of("America/Argentina/Buenos_Aires"));

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
            case EMAIL_CODE -> new EmailMessage(recipient, "Deliora: tu código es " + payload,
                    "Toca este enlace para verificar tu correo y seguir con tu compra:\n\n"
                            + baseUrl + "/comprar/verificar?email=" + java.net.URLEncoder.encode(recipient, java.nio.charset.StandardCharsets.UTF_8)
                            + "&code=" + payload
                            + "\n\nO escribe este código en la página de compra:\n\n" + payload
                            + "\n\nVale 15 minutos. Si no fuiste tú, ignora este mensaje.");
            case PAYMENT_RECEIPT -> {
                String[] parts = payload.split("\\|", 4);
                yield new EmailMessage(recipient, "Recibo de tu pago en Deliora",
                        "Recibimos tu pago de " + parts[2] + " por el plan " + parts[1] + ". Gracias por confiar en Deliora.\n\n"
                                + "Tu negocio ya está creado: " + baseUrl + "/" + parts[0] + "\n"
                                + "Entra a tu panel con tu correo y la contraseña que elegiste: " + baseUrl + "/auth/login");
            }
            case PLAN_NOTICE -> planNotice(recipient, payload);
            case RENEWAL_RECEIPT -> {
                String[] parts = payload.split("\\|", 4);
                yield new EmailMessage(recipient, "Renovamos tu plan de Deliora",
                        "Recibimos tu pago de " + parts[2] + " y renovamos tu plan " + parts[1] + ". Gracias por seguir con Deliora.\n\n"
                                + "Tu plan ahora vence el " + DATE.format(java.time.Instant.parse(parts[3])) + ".\n"
                                + "Tu sitio: " + baseUrl + "/" + parts[0] + "\n"
                                + "Tu panel: " + baseUrl + "/admin");
            }
            default -> throw new IllegalArgumentException("Tipo de correo desconocido: " + type);
        };
    }

    private EmailMessage planNotice(String recipient, String payload) {
        String[] parts = payload.split("\\|", 4);
        String kind = parts[0];
        String site = baseUrl + "/" + parts[1];
        java.time.Instant expires = java.time.Instant.parse(parts[2]);
        String name = parts[3];
        String renew = baseUrl + "/admin/plan";
        String expiresOn = DATE.format(expires);
        String pausesOn = DATE.format(expires.plus(java.time.Duration.ofDays(3)));
        String archivesOn = DATE.format(expires.plus(java.time.Duration.ofDays(90)));
        String howTo = "\n\nRenuévalo en un minuto con PayPal o Mercado Pago (o con un código de activación):\n" + renew;
        return switch (kind) {
            case "SOON_7", "SOON_3", "SOON_1" -> {
                String when = "SOON_1".equals(kind) ? "mañana" : "SOON_3".equals(kind) ? "en 3 días" : "en 7 días";
                yield new EmailMessage(recipient, "Tu plan de Deliora vence " + when,
                        "Hola. El plan de " + name + " vence " + when + " (" + expiresOn + ").\n\n"
                                + "Si lo renuevas antes, tu carta, tu código QR y las reservas por WhatsApp siguen funcionando sin cortes."
                                + howTo + "\n\nEl tiempo nuevo se suma al que te queda: no pierdes ningún día.");
            }
            case "GRACE" -> new EmailMessage(recipient, "Tu plan venció: tienes 3 días para renovarlo",
                    "Hola. El plan de " + name + " venció el " + expiresOn + ".\n\n"
                            + "Por cortesía, todo sigue funcionando hasta el " + pausesOn + ". Ese día tu sitio se pausa: "
                            + "quien escanee tu código QR verá un aviso en lugar de tu carta." + howTo);
            case "PAUSED", "PAUSED_7", "PAUSED_30" -> new EmailMessage(recipient,
                    "PAUSED".equals(kind) ? "Pausamos tu sitio de Deliora" : "Tu sitio de Deliora sigue en pausa",
                    "Hola. Como el plan de " + name + " no se renovó, tu sitio " + site + " está en pausa: "
                            + "tus clientes ven un aviso en lugar de tu carta y no pueden reservar.\n\n"
                            + "Tu carta, tus fotos y tus datos están guardados. Al renovar, todo vuelve a funcionar al instante."
                            + howTo + ("PAUSED_30".equals(kind) ? "\n\nSi no lo renuevas, el " + archivesOn + " archivaremos tu sitio." : ""));
            case "ARCHIVED" -> new EmailMessage(recipient, "Archivamos tu sitio de Deliora",
                    "Hola. Pasaron 90 días desde que venció el plan de " + name + ", así que archivamos tu sitio y ya no aparece en internet.\n\n"
                            + "No borramos nada: tu carta y tus datos siguen guardados. Si quieres volver, entra y renueva cuando quieras:\n" + renew);
            default -> throw new IllegalArgumentException("Aviso de plan desconocido: " + kind);
        };
    }
}

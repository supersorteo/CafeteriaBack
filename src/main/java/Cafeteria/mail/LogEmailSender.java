package Cafeteria.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Solo desarrollo local: imprime el enlace con el token. Producción debe usar mail.sender=smtp.
@Component
@ConditionalOnProperty(name = "mail.sender", havingValue = "log")
public class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.info("[correo de desarrollo] a={} asunto={}\n{}", message.to(), message.subject(), message.body());
    }
}

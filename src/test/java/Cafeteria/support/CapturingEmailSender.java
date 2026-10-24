package Cafeteria.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import Cafeteria.mail.EmailMessage;
import Cafeteria.mail.EmailSender;

public class CapturingEmailSender implements EmailSender {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    public final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    public volatile boolean failing;

    @Override
    public void send(EmailMessage message) {
        if (failing) {
            throw new IllegalStateException("smtp caído");
        }
        sent.add(message);
    }

    public EmailMessage last() {
        return sent.get(sent.size() - 1);
    }

    public String lastToken() {
        Matcher matcher = TOKEN.matcher(last().body());
        if (!matcher.find()) {
            throw new AssertionError("El correo no contiene token: " + last().body());
        }
        return matcher.group(1);
    }

    public void reset() {
        sent.clear();
        failing = false;
    }
}

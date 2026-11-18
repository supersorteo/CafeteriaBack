package Cafeteria.plan;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;

public final class ActivationCodeFormat {

    public static final String PREFIX = "DLR";
    public static final Set<Integer> DURATIONS = Set.of(1, 3, 6, 12);

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ActivationCodeFormat() {}

    public static String generate() {
        StringBuilder code = new StringBuilder(PREFIX).append('-');
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String compact = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        if (compact.startsWith(PREFIX)) {
            compact = compact.substring(PREFIX.length());
        }
        if (compact.length() != 8) {
            return "";
        }
        for (char c : compact.toCharArray()) {
            if (ALPHABET.indexOf(c) < 0) {
                return "";
            }
        }
        return PREFIX + "-" + compact.substring(0, 4) + "-" + compact.substring(4);
    }
}

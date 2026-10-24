package Cafeteria.tenant;

import java.util.Set;

public final class SlugRules {

    public static final String PATTERN = "^[a-z0-9]([a-z0-9]|-[a-z0-9])*$";
    public static final Set<String> RESERVED =
            Set.of("admin", "api", "media", "assets", "images", "auth", "www", "support", "status");

    private SlugRules() {}
}

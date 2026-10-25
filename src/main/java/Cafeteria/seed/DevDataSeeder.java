package Cafeteria.seed;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import Cafeteria.auth.User;
import Cafeteria.auth.UserRepository;
import Cafeteria.seed.DemoSiteBuilder.Item;
import Cafeteria.seed.DemoSiteBuilder.Section;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;
import Cafeteria.tenant.TenantUser;
import Cafeteria.tenant.TenantUserRepository;

@Component
@ConditionalOnProperty(name = "seed.dev.enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final TenantRepository tenants;
    private final UserRepository users;
    private final TenantUserRepository memberships;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final DemoSiteBuilder demo;
    private final String password;

    public DevDataSeeder(TenantRepository tenants, UserRepository users, TenantUserRepository memberships,
            PasswordEncoder encoder, TransactionTemplate tx, DemoSiteBuilder demo,
            @Value("${seed.dev.password:}") String password) {
        this.tenants = tenants;
        this.users = users;
        this.memberships = memberships;
        this.encoder = encoder;
        this.tx = tx;
        this.demo = demo;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password.isBlank()) {
            log.warn("seed.dev.password vacío: seed omitido");
            return;
        }
        tx.executeWithoutResult(status -> {
            if (!tenants.existsBySlug("la-terraza")) {
                Tenant a = createTenant("la-terraza", "La Terraza", true);
                createUser("owner-a@laterraza.test", a, Role.OWNER);
                createUser("editor-a@laterraza.test", a, Role.EDITOR);
            }
            tenants.findBySlug("la-terraza").ifPresent(demo::populate);
            if (!tenants.existsBySlug("bistro-demo")) {
                Tenant b = createTenant("bistro-demo", "Bistro Demo", true);
                createUser("owner-b@bistro.test", b, Role.OWNER);
                demo.importMenu(b, List.of(new Section("Platos del día", List.of(
                        new Item("Milanesa napolitana", "Con papas fritas.", "350", "Casero"),
                        new Item("Ensalada César", "Pollo, crutones y parmesano.", "280", "Liviano")))));
            }
        });
    }

    private Tenant createTenant(String slug, String name, boolean published) {
        Tenant tenant = new Tenant(slug, name);
        tenant.setPublished(published);
        return tenants.save(tenant);
    }

    private void createUser(String email, Tenant tenant, Role role) {
        User user = new User(email, encoder.encode(password));
        user.setEmailVerifiedAt(Instant.now());
        users.save(user);
        memberships.save(new TenantUser(user.getId(), tenant.getId(), role));
    }
}

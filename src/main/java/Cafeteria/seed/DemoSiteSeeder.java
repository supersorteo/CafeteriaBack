package Cafeteria.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;
import Cafeteria.tenant.TenantUserRepository;

@Component
@ConditionalOnProperty(name = "demo.site.enabled", havingValue = "true")
public class DemoSiteSeeder implements ApplicationRunner {

    public static final String SLUG = "la-terraza";

    private static final Logger log = LoggerFactory.getLogger(DemoSiteSeeder.class);

    private final TenantRepository tenants;
    private final TenantUserRepository memberships;
    private final DemoSiteBuilder builder;
    private final TransactionTemplate tx;

    public DemoSiteSeeder(TenantRepository tenants, TenantUserRepository memberships, DemoSiteBuilder builder,
            TransactionTemplate tx) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.builder = builder;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            tx.executeWithoutResult(status -> {
                Tenant tenant = tenants.findBySlug(SLUG).orElseGet(() -> {
                    Tenant created = new Tenant(SLUG, "La Terraza");
                    created.setPublished(true);
                    return tenants.save(created);
                });
                if (memberships.existsByTenantId(tenant.getId())) {
                    log.info("El negocio '{}' tiene dueño: no se toca la demo", SLUG);
                    return;
                }
                builder.populate(tenant);
                log.info("Sitio de demostración '{}' listo", SLUG);
            });
        } catch (RuntimeException e) {
            log.warn("No se pudo preparar el sitio de demostración: {}", e.getMessage());
        }
    }
}

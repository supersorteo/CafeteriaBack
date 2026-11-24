package Cafeteria.superadmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SuperAdminAccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminAccountBootstrap.class);

    private final SuperAdminAccounts accounts;
    private final SuperAdminProperties primary;
    private final String email;
    private final String password;
    private final String role;
    private final String regions;

    public SuperAdminAccountBootstrap(SuperAdminAccounts accounts, SuperAdminProperties primary,
            @Value("${superadmin.operator.email:}") String email, @Value("${superadmin.operator.password:}") String password,
            @Value("${superadmin.operator.role:OPERATOR}") String role,
            @Value("${superadmin.operator.regions:AR}") String regions) {
        this.accounts = accounts;
        this.primary = primary;
        this.email = SuperAdminAccounts.normalize(email);
        this.password = password == null ? "" : password;
        this.role = role == null || role.isBlank() ? SuperAdminAccounts.OPERATOR : role.strip().toUpperCase();
        this.regions = regions;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isEmpty() && password.isEmpty()) {
            return;
        }
        if (email.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException("SUPERADMIN_OPERATOR_EMAIL y SUPERADMIN_OPERATOR_PASSWORD deben definirse juntos");
        }
        if (email.equals(primary.email())) {
            log.warn("La cuenta adicional de superadmin coincide con la principal; se ignora");
            return;
        }
        if (accounts.find(email).isPresent()) {
            return;
        }
        SuperAdminAccounts.Account created = accounts.create(email, password, role, regions, true);
        accounts.audit("SUPERADMIN_ACCOUNT_CREATED", created.id(), created.email() + " (" + created.role() + ")", "sistema");
        log.info("Cuenta de superadmin creada: rol {}. Deberá cambiar su contraseña al primer ingreso.", created.role());
    }
}

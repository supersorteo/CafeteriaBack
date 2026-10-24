package Cafeteria.auth;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import Cafeteria.auth.AccountDtos.RegisterRequest;
import Cafeteria.shared.ApiException;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.SlugRules;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;
import Cafeteria.tenant.TenantUser;
import Cafeteria.tenant.TenantUserRepository;

@Service
public class RegistrationService {

    private final UserRepository users;
    private final TenantRepository tenants;
    private final TenantUserRepository memberships;
    private final AccountService accounts;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    public RegistrationService(UserRepository users, TenantRepository tenants, TenantUserRepository memberships,
            AccountService accounts, PasswordEncoder encoder, TransactionTemplate tx) {
        this.users = users;
        this.tenants = tenants;
        this.memberships = memberships;
        this.accounts = accounts;
        this.encoder = encoder;
        this.tx = tx;
    }

    public void register(RegisterRequest request) {
        String email = AccountService.normalize(request.email());
        if (SlugRules.RESERVED.contains(request.slug())) {
            throw ApiException.badRequest("slug_reserved", "Ese identificador no está disponible");
        }
        Optional<User> existing = users.findByEmail(email);
        if (existing.isPresent()) {
            tx.executeWithoutResult(status -> handleExisting(existing.get()));
            return;
        }
        String passwordHash = encoder.encode(request.password());
        try {
            tx.executeWithoutResult(status -> create(email, passwordHash, request));
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("tenants_slug_uk")) {
                throw ApiException.conflict("slug_taken", "Ese identificador ya está en uso");
            }
            if (message.contains("users_email_uk")) {
                return;
            }
            throw e;
        }
    }

    private void handleExisting(User user) {
        if (user.isEnabled() && user.getEmailVerifiedAt() == null) {
            accounts.issueVerification(user);
        } else {
            accounts.notifyAccountExists(user);
        }
    }

    private void create(String email, String passwordHash, RegisterRequest request) {
        User user = users.saveAndFlush(new User(email, passwordHash));
        Tenant tenant = new Tenant(request.slug(), request.businessName().strip());
        tenants.saveAndFlush(tenant);
        memberships.saveAndFlush(new TenantUser(user.getId(), tenant.getId(), Role.OWNER));
        accounts.issueVerification(user);
    }
}

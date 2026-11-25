package Cafeteria.auth;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import Cafeteria.auth.AccountDtos.RegisterRequest;
import Cafeteria.plan.PlanService;
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
    private final PlanService plans;

    public RegistrationService(UserRepository users, TenantRepository tenants, TenantUserRepository memberships,
            AccountService accounts, PasswordEncoder encoder, TransactionTemplate tx, PlanService plans) {
        this.users = users;
        this.tenants = tenants;
        this.memberships = memberships;
        this.accounts = accounts;
        this.encoder = encoder;
        this.tx = tx;
        this.plans = plans;
    }

    public boolean register(RegisterRequest request) {
        String email = AccountService.normalize(request.email());
        if (SlugRules.RESERVED.contains(request.slug())) {
            throw ApiException.badRequest("slug_reserved", "Ese identificador no está disponible");
        }
        if (!plans.isAvailable(request.activationCode())) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid_code",
                    "El código no es válido o ya fue usado");
        }
        Optional<User> existing = users.findByEmail(email);
        if (existing.isPresent()) {
            tx.executeWithoutResult(status -> handleExisting(existing.get()));
            return false;
        }
        String passwordHash = encoder.encode(request.password());
        try {
            return Boolean.TRUE.equals(tx.execute(status -> create(email, passwordHash, request)));
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("tenants_slug_uk")) {
                throw ApiException.conflict("slug_taken", "Ese identificador ya está en uso");
            }
            if (message.contains("users_email_uk")) {
                return false;
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

    private boolean create(String email, String passwordHash, RegisterRequest request) {
        User user = users.saveAndFlush(new User(email, passwordHash));
        Tenant tenant = new Tenant(request.slug(), request.businessName().strip());
        tenants.saveAndFlush(tenant);
        Cafeteria.plan.PlanService.Redemption redemption = plans.redeemForRegistration(request.activationCode(), tenant.getId(), email);
        tenant.setPlanExpiresAt(redemption.expiresAt());
        tenant.setPlanMonths(redemption.months());
        tenant.setPlanCode(redemption.planCode());
        tenants.saveAndFlush(tenant);
        memberships.saveAndFlush(new TenantUser(user.getId(), tenant.getId(), Role.OWNER));
        boolean paidWithThisEmail = plans.buyerEmailOfCode(request.activationCode()).map(email::equals).orElse(false);
        if (paidWithThisEmail) {
            user.setEmailVerifiedAt(java.time.Instant.now());
            users.saveAndFlush(user);
            return true;
        }
        accounts.issueVerification(user);
        return false;
    }
}

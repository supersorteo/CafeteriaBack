package Cafeteria.billing;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.auth.User;
import Cafeteria.auth.UserRepository;
import Cafeteria.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/admin/plan")
public class AdminRenewalController {

    public record RenewalRequest(@NotBlank @Size(max = 20) String planCode, @NotBlank @Size(max = 2) String region,
            @NotBlank @Size(max = 20) String method) {}

    private final TenantContext tenantContext;
    private final UserRepository users;
    private final OnlineOrderService online;

    public AdminRenewalController(TenantContext tenantContext, UserRepository users, OnlineOrderService online) {
        this.tenantContext = tenantContext;
        this.users = users;
        this.online = online;
    }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public OnlineOrderService.Checkout checkout(@Valid @RequestBody RenewalRequest body) {
        TenantContext.Current current = tenantContext.requireOwner();
        User user = users.findById(current.userId()).orElseThrow();
        return online.createRenewal(current.tenantId(), user.getEmail(), body.planCode(), body.region(), body.method());
    }
}

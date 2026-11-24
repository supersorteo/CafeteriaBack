package Cafeteria.superadmin;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.security.ClientIp;
import Cafeteria.superadmin.SuperAdminDtos.AuditEntry;
import Cafeteria.superadmin.SuperAdminDtos.DeleteRequest;
import Cafeteria.superadmin.SuperAdminDtos.Stats;
import Cafeteria.superadmin.SuperAdminDtos.TenantPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/superadmin")
public class SuperAdminController {

    private final SuperAdminService service;
    private final SuperAdminScopes scopes;
    private final boolean trustForwardedFor;

    public SuperAdminController(SuperAdminService service, SuperAdminScopes scopes,
            @Value("${security.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.service = service;
        this.scopes = scopes;
        this.trustForwardedFor = trustForwardedFor;
    }

    @GetMapping("/stats")
    public Stats stats(@RequestParam(required = false) String scope, Authentication auth) {
        return service.stats(scopes.viewer(auth, scope));
    }

    @GetMapping("/tenants")
    public TenantPage tenants(@RequestParam(required = false) String query, @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "all") String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return service.list(scopes.viewer(auth, scope), query, status, page, size);
    }

    @PostMapping("/tenants/{id}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(@PathVariable UUID id, Authentication auth, HttpServletRequest request) {
        scopes.requireTenant(scopes.viewer(auth, null), id);
        service.suspend(id, auth.getName(), ClientIp.resolve(request, trustForwardedFor));
    }

    @PostMapping("/tenants/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable UUID id, Authentication auth, HttpServletRequest request) {
        scopes.requireTenant(scopes.viewer(auth, null), id);
        service.activate(id, auth.getName(), ClientIp.resolve(request, trustForwardedFor));
    }

    @DeleteMapping("/tenants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @Valid @RequestBody DeleteRequest body, Authentication auth,
            HttpServletRequest request) {
        scopes.requireTenant(scopes.viewer(auth, null), id);
        service.delete(id, body.confirmSlug(), auth.getName(), ClientIp.resolve(request, trustForwardedFor));
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String scope,
            Authentication auth) {
        return service.audit(scopes.viewer(auth, scope), limit);
    }
}

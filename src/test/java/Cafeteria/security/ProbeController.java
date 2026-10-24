package Cafeteria.security;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/probe")
class ProbeController {

    private final TenantContext tenantContext;

    ProbeController(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @GetMapping("/tenant")
    UUID tenant() {
        return tenantContext.require().tenantId();
    }

    @GetMapping("/owner")
    String owner() {
        tenantContext.requireOwner();
        return "ok";
    }
}

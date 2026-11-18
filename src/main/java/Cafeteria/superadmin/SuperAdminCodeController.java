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

import Cafeteria.plan.PlanService;
import Cafeteria.security.ClientIp;
import Cafeteria.superadmin.SuperAdminCodeService.BatchRow;
import Cafeteria.superadmin.SuperAdminCodeService.CodePage;
import Cafeteria.superadmin.SuperAdminCodeService.CodeStats;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/superadmin/codes")
public class SuperAdminCodeController {

    public record BatchRequest(@Size(max = 80) String label, @NotNull Integer durationMonths,
            @NotNull @Min(1) @Max(500) Integer quantity, @Size(max = 300) String note) {}

    private final SuperAdminCodeService service;
    private final boolean trustForwardedFor;

    public SuperAdminCodeController(SuperAdminCodeService service,
            @Value("${security.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.service = service;
        this.trustForwardedFor = trustForwardedFor;
    }

    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanService.Batch createBatch(@Valid @RequestBody BatchRequest body, Authentication auth, HttpServletRequest request) {
        return service.createBatch(body.label(), body.durationMonths(), body.quantity(), body.note(), auth.getName(),
                ClientIp.resolve(request, trustForwardedFor));
    }

    @GetMapping("/batches")
    public List<BatchRow> batches() {
        return service.batches();
    }

    @GetMapping("/stats")
    public CodeStats stats() {
        return service.stats();
    }

    @GetMapping
    public CodePage list(@RequestParam(required = false) String status, @RequestParam(required = false) Integer durationMonths,
            @RequestParam(required = false) UUID batchId, @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(status, durationMonths, batchId, query, page, size);
    }

    @PostMapping("/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable UUID id, Authentication auth, HttpServletRequest request) {
        service.disable(id, auth.getName(), ClientIp.resolve(request, trustForwardedFor));
    }

    @PostMapping("/{id}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enable(@PathVariable UUID id, Authentication auth, HttpServletRequest request) {
        service.enable(id, auth.getName(), ClientIp.resolve(request, trustForwardedFor));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestBody(required = false) SuperAdminDtos.OptionalDelete body,
            Authentication auth, HttpServletRequest request) {
        service.delete(id, body == null ? null : body.confirmSlug(), auth.getName(), ClientIp.resolve(request, trustForwardedFor));
    }
}

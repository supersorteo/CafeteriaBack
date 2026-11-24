package Cafeteria.billing;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/public/billing")
public class PublicBillingController {

    public record OrderRequest(@NotBlank @Size(max = 20) String planCode, @NotBlank @Size(max = 2) String region,
            @NotBlank @Email @Size(max = 254) String email, @Size(max = 120) String name) {}

    private final BillingService billing;

    public PublicBillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/options")
    public BillingService.Options options() {
        return billing.options();
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingService.OrderReceipt create(@Valid @RequestBody OrderRequest body) {
        return billing.createOrder(new BillingService.NewOrder(body.planCode(), body.region(), body.email(), body.name()));
    }

    @GetMapping("/orders/{reference}")
    public BillingService.OrderReceipt order(@PathVariable String reference) {
        return billing.receipt(reference);
    }
}

package Cafeteria.billing;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.auth.User;
import Cafeteria.shared.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/public/billing")
public class OnlineBillingController {

    public record CheckoutBody(@NotBlank @Size(max = 20) String planCode, @NotBlank @Size(max = 2) String region,
            @NotBlank @Size(max = 20) String method, @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String businessName, @NotBlank @Size(max = 63) String slug,
            @NotBlank @Size(max = 100) String password, @NotBlank @Size(max = 80) String emailProof) {}

    public record EmailBody(@NotBlank @Email @Size(max = 254) String email) {}

    public record EmailCodeBody(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 12) String code) {}

    public record ProofResponse(String proof) {}

    public record VerifyBody(@Size(max = 80) String paymentId) {}

    public record SessionBody(@NotBlank @Size(max = 80) String claimToken) {}

    public record SessionResponse(String status) {}

    private final OnlineOrderService online;
    private final OnlineSessionLogin login;
    private final EmailProofService proofs;

    public OnlineBillingController(OnlineOrderService online, OnlineSessionLogin login, EmailProofService proofs) {
        this.proofs = proofs;
        this.online = online;
        this.login = login;
    }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public OnlineOrderService.Checkout checkout(@Valid @RequestBody CheckoutBody body) {
        return online.createCheckout(new OnlineOrderService.CheckoutRequest(body.planCode(), body.region(), body.method(),
                body.email(), body.businessName(), body.slug(), body.password(), body.emailProof()));
    }

    @PostMapping("/email/code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendCode(@Valid @RequestBody EmailBody body) {
        proofs.sendCode(body.email());
    }

    @PostMapping("/email/verify")
    public ProofResponse verifyCode(@Valid @RequestBody EmailCodeBody body) {
        return new ProofResponse(proofs.verify(body.email(), body.code()));
    }

    @GetMapping("/orders/{reference}/state")
    public OnlineOrderService.State state(@PathVariable String reference) {
        return online.state(reference);
    }

    @PostMapping("/orders/{reference}/paypal/capture")
    public OnlineOrderService.State capture(@PathVariable String reference) {
        return online.capturePayPal(reference);
    }

    @PostMapping("/orders/{reference}/mercadopago/verify")
    public OnlineOrderService.State verify(@PathVariable String reference, @RequestBody(required = false) VerifyBody body) {
        return online.verifyMercadoPago(reference, body == null ? null : body.paymentId());
    }

    @PostMapping("/orders/{reference}/session")
    public SessionResponse session(@PathVariable String reference, @Valid @RequestBody SessionBody body,
            HttpServletRequest request, HttpServletResponse response) {
        User user = online.claim(reference, body.claimToken())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "claim_invalid", "No se pudo iniciar la sesión automática. Inicia sesión con tu correo."));
        login.login(user, request, response);
        return new SessionResponse("ok");
    }

    @PostMapping("/webhooks/mercadopago")
    public org.springframework.http.ResponseEntity<Void> mercadopago(
            @RequestHeader(value = "x-signature", required = false) String signature,
            @RequestHeader(value = "x-request-id", required = false) String requestId,
            @RequestParam(value = "data.id", required = false) String dataId,
            @RequestParam(value = "type", required = false) String type) {
        boolean valid = online.handleMercadoPagoNotification(signature, requestId, dataId, type);
        return valid ? org.springframework.http.ResponseEntity.ok().build()
                : org.springframework.http.ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}

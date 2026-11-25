package Cafeteria.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.auth.AccountDtos.EmailRequest;
import Cafeteria.auth.AccountDtos.MessageResponse;
import Cafeteria.auth.AccountDtos.RegisterRequest;
import Cafeteria.auth.AccountDtos.ResetPasswordRequest;
import Cafeteria.auth.AccountDtos.TokenRequest;
import Cafeteria.shared.ApiException;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {

    private final RegistrationService registration;
    private final AccountService accounts;
    private final boolean registrationEnabled;

    public AccountController(RegistrationService registration, AccountService accounts,
            @Value("${registration.enabled:false}") boolean registrationEnabled) {
        this.registration = registration;
        this.accounts = accounts;
        this.registrationEnabled = registrationEnabled;
    }

    public record RegisterResponse(String message, boolean verified) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        if (!registrationEnabled) {
            throw new ApiException(HttpStatus.FORBIDDEN, "registration_disabled", "El registro no está disponible");
        }
        boolean verified = registration.register(request);
        return new RegisterResponse(verified ? "Cuenta creada" : "Revisa tu correo para confirmar la cuenta", verified);
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody TokenRequest request) {
        accounts.verifyEmail(request.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse resendVerification(@Valid @RequestBody EmailRequest request) {
        accounts.resendVerification(request.email());
        return new MessageResponse("Si la cuenta existe y está pendiente, recibirás un correo");
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse forgotPassword(@Valid @RequestBody EmailRequest request) {
        accounts.forgotPassword(request.email());
        return new MessageResponse("Si la cuenta existe, recibirás un correo con instrucciones");
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        accounts.resetPassword(request.token(), request.newPassword());
    }
}

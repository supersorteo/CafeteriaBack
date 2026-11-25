package Cafeteria.billing;

public class PaymentProviderException extends RuntimeException {

    private final int status;

    public PaymentProviderException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}

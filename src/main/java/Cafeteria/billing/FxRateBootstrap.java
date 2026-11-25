package Cafeteria.billing;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class FxRateBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FxRateBootstrap.class);

    private final FxRates rates;
    private final String initial;

    public FxRateBootstrap(FxRates rates, @Value("${billing.usd-ars-rate:}") String initial) {
        this.rates = rates;
        this.initial = initial == null ? "" : initial.strip();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (initial.isEmpty() || rates.usdToArs().isPresent()) {
            return;
        }
        try {
            BigDecimal rate = new BigDecimal(initial);
            if (rate.signum() > 0) {
                rates.record(rate, "MANUAL", "config");
                log.info("Cotizacion USD/ARS inicial cargada desde la configuracion");
            }
        } catch (NumberFormatException e) {
            log.warn("BILLING_USD_ARS_RATE no es un numero valido; Mercado Pago quedara sin precio en pesos");
        }
    }
}

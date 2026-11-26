package Cafeteria.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import Cafeteria.mail.EmailMessage;
import Cafeteria.mail.EmailTemplates;
import Cafeteria.support.IntegrationTestSupport;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;

class PlanNoticesTest extends IntegrationTestSupport {

    @Autowired
    PlanNoticeService notices;

    private Tenant owner(String slug, Instant expires) {
        Tenant tenant = tenant(slug);
        tenant.setPlanExpiresAt(expires);
        tenant = tenants.save(tenant);
        member("owner@" + slug + ".test", tenant, Role.OWNER, true);
        member("editor@" + slug + ".test", tenant, Role.EDITOR, true);
        return tenant;
    }

    private List<String> sent() {
        return jdbc.queryForList("SELECT recipient || ' ' || split_part(payload, '|', 1) FROM email_outbox WHERE type = 'PLAN_NOTICE' ORDER BY recipient",
                String.class);
    }

    @Test
    void avisaUnaSolaVezPorEtapaSoloAlDuenoYVuelveAAvisarEnElPeriodoSiguiente() {
        owner("siete", Instant.now().plus(6, ChronoUnit.DAYS));
        owner("tres", Instant.now().plus(2, ChronoUnit.DAYS));
        owner("manana", Instant.now().plus(5, ChronoUnit.HOURS));
        owner("gracia", Instant.now().minus(1, ChronoUnit.DAYS));
        owner("pausa", Instant.now().minus(4, ChronoUnit.DAYS));
        owner("semana", Instant.now().minus(15, ChronoUnit.DAYS));
        owner("mes", Instant.now().minus(40, ChronoUnit.DAYS));
        owner("archivo", Instant.now().minus(92, ChronoUnit.DAYS));
        owner("tranquilo", Instant.now().plus(20, ChronoUnit.DAYS));
        owner("olvidado", Instant.now().minus(200, ChronoUnit.DAYS));
        Tenant suspendido = owner("suspendido", Instant.now().plus(2, ChronoUnit.DAYS));
        suspendido.setSuspendedAt(Instant.now());
        tenants.save(suspendido);

        assertThat(notices.sendDue()).isEqualTo(8);
        assertThat(sent()).containsExactly("owner@archivo.test ARCHIVED", "owner@gracia.test GRACE", "owner@manana.test SOON_1",
                "owner@mes.test PAUSED_30", "owner@pausa.test PAUSED", "owner@semana.test PAUSED_7", "owner@siete.test SOON_7",
                "owner@tres.test SOON_3");

        assertThat(notices.sendDue()).isZero();

        Tenant renovado = tenants.findBySlug("gracia").orElseThrow();
        renovado.setPlanExpiresAt(Instant.now().plus(5, ChronoUnit.DAYS));
        tenants.save(renovado);
        assertThat(notices.sendDue()).isEqualTo(1);
        assertThat(sent()).contains("owner@gracia.test SOON_7");
    }

    @Test
    void losCorreosExplicanQueSigueComoRenovarYCuandoSePausa() {
        EmailTemplates templates = new EmailTemplates("https://app.ejemplo.test");
        Instant expires = Instant.parse("2026-12-10T15:00:00Z");

        EmailMessage soon = templates.build(EmailTemplates.PLAN_NOTICE, "a@b.test", "SOON_3|cafe-ana|" + expires + "|Café Ana");
        assertThat(soon.subject()).isEqualTo("Tu plan de Deliora vence en 3 días");
        assertThat(soon.body()).contains("Café Ana").contains("10/12/2026").contains("https://app.ejemplo.test/admin/plan");

        EmailMessage grace = templates.build(EmailTemplates.PLAN_NOTICE, "a@b.test", "GRACE|cafe-ana|" + expires + "|Café Ana");
        assertThat(grace.body()).contains("hasta el 13/12/2026");

        EmailMessage paused = templates.build(EmailTemplates.PLAN_NOTICE, "a@b.test", "PAUSED_30|cafe-ana|" + expires + "|Café Ana");
        assertThat(paused.body()).contains("https://app.ejemplo.test/cafe-ana").contains("archivaremos").contains("10/03/2027");

        EmailMessage receipt = templates.build(EmailTemplates.RENEWAL_RECEIPT, "a@b.test", "cafe-ana|Profesional|US$ 19|" + expires);
        assertThat(receipt.body()).contains("US$ 19").contains("Profesional").contains("10/12/2026");
    }
}

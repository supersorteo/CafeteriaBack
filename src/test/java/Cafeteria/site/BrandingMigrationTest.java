package Cafeteria.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import Cafeteria.support.IntegrationTestSupport;

class BrandingMigrationTest extends IntegrationTestSupport {

    @Value("${spring.datasource.url}")
    String url;
    @Value("${spring.datasource.username}")
    String user;
    @Value("${spring.datasource.password}")
    String password;

    private DataSource dataSource() {
        return new DriverManagerDataSource(url.replace("currentSchema=test_it", "currentSchema=v7_mig"), user, password);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource()).schemas("v7_mig").defaultSchema("v7_mig")
                .cleanDisabled(false).target(target == null ? "latest" : target).load();
    }

    @Test
    void laDuracionDelCodigoCanjeadoPasaAlNegocioYLoDemasQuedaSinPersonalizar() throws Exception {
        flyway("6").clean();
        flyway("6").migrate();
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenants (id, slug, name) VALUES "
                    + "('00000000-0000-0000-0000-00000000000a', 'con-codigo', 'A'),"
                    + "('00000000-0000-0000-0000-00000000000b', 'sin-codigo', 'B')");
            st.execute("INSERT INTO code_batches (id, label, duration_months, quantity, created_by) VALUES "
                    + "('00000000-0000-0000-0000-0000000000c1', 'Lote', 6, 1, 'root')");
            st.execute("INSERT INTO activation_codes (id, code, batch_id, duration_months, status, tenant_id) VALUES "
                    + "(gen_random_uuid(), 'DLR-AAAA-BBBB', '00000000-0000-0000-0000-0000000000c1', 6, 'REDEEMED', "
                    + "'00000000-0000-0000-0000-00000000000a')");

            flyway(null).migrate();

            try (ResultSet rs = st.executeQuery("SELECT slug, plan_months, footer_color, font_family, qr_use_logo FROM tenants ORDER BY slug")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("slug")).isEqualTo("con-codigo");
                assertThat(rs.getInt("plan_months")).isEqualTo(6);
                assertThat(rs.getString("footer_color")).isNull();
                assertThat(rs.getBoolean("qr_use_logo")).isFalse();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("slug")).isEqualTo("sin-codigo");
                rs.getInt("plan_months");
                assertThat(rs.wasNull()).isTrue();
            }
            try {
                st.execute("UPDATE tenants SET font_family = 'comic-sans'");
                throw new AssertionError("la base debería rechazar una fuente fuera de la lista");
            } catch (java.sql.SQLException expected) {
                assertThat(expected.getMessage()).contains("tenants_font_family_ck");
            }
        } finally {
            flyway(null).clean();
        }
    }
}

package Cafeteria.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;

import Cafeteria.support.IntegrationTestSupport;

class SiteSpacesMigrationTest extends IntegrationTestSupport {

    @Value("${spring.datasource.url}")
    String url;
    @Value("${spring.datasource.username}")
    String user;
    @Value("${spring.datasource.password}")
    String password;

    private DataSource dataSource() {
        return new DriverManagerDataSource(url.replace("currentSchema=test_it", "currentSchema=v4_mig"), user, password);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource()).schemas("v4_mig").defaultSchema("v4_mig")
                .cleanDisabled(false).target(target == null ? "latest" : target).load();
    }

    @Test
    void lasTarjetasYLasZonasAntiguasSeConviertenEnEspaciosSinPerderNada() throws Exception {
        flyway("3").clean();
        flyway("3").migrate();
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenants (id, slug, name, reservation_areas) VALUES "
                    + "('00000000-0000-0000-0000-00000000000a', 'negocio-a', 'A', "
                    + "ARRAY['terraza ', 'Barra', 'Salón', 'BARRA'])");
            st.execute("INSERT INTO tenants (id, slug, name) VALUES ('00000000-0000-0000-0000-00000000000b', 'negocio-b', 'B')");
            st.execute("INSERT INTO site_highlights (id, tenant_id, position, title, description) VALUES "
                    + "(gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', 0, 'Terraza', 'Aire libre'),"
                    + "(gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', 1, 'Happy hour', 'Martes'),"
                    + "(gen_random_uuid(), '00000000-0000-0000-0000-00000000000b', 0, 'Solo tarjeta', NULL)");

            flyway(null).migrate();

            List<String> rows = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT s.position, s.name, s.description, s.bookable, t.slug "
                    + "FROM site_spaces s JOIN tenants t ON t.id = s.tenant_id ORDER BY t.slug, s.position")) {
                while (rs.next()) {
                    rows.add(rs.getString("slug") + "|" + rs.getInt("position") + "|" + rs.getString("name") + "|"
                            + rs.getString("description") + "|" + rs.getBoolean("bookable"));
                }
            }
            assertThat(rows).containsExactly(
                    "negocio-a|0|Terraza|Aire libre|true",
                    "negocio-a|1|Happy hour|Martes|false",
                    "negocio-a|2|Barra|null|true",
                    "negocio-a|3|Salón|null|true",
                    "negocio-b|0|Solo tarjeta|null|false");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM site_highlights")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(3);
            }
        } finally {
            flyway(null).clean();
        }
    }
}

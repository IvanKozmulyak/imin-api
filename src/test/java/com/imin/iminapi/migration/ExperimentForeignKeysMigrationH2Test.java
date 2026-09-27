package com.imin.iminapi.migration;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/** V154 on H2 in the test profile's PostgreSQL mode. */
class ExperimentForeignKeysMigrationH2Test extends ExperimentForeignKeysMigrationScenarios {

    @Override
    DataSource freshDatabase() {
        return new DriverManagerDataSource("jdbc:h2:mem:v154-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
    }
}

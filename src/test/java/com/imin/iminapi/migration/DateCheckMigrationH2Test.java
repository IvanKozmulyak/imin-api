package com.imin.iminapi.migration;

import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * V162 on H2 in the test profile's PostgreSQL mode. One shared connection, like the app's pool:
 * H2 binds an IN-list CHECK to the session that created it and fails once that session closes.
 */
class DateCheckMigrationH2Test extends DateCheckMigrationScenarios {

    @Override
    DataSource freshDatabase() {
        return new SingleConnectionDataSource("jdbc:h2:mem:v162-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "", true);
    }
}

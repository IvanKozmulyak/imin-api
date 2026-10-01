package com.imin.iminapi.migration;

import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/** The venue coordinate CHECK on H2 in the test profile's PostgreSQL mode, one shared connection like the app's pool. */
class VenueCoordsConstraintH2Test extends VenueCoordsConstraintScenarios {

    @Override
    DataSource freshDatabase() {
        return new SingleConnectionDataSource("jdbc:h2:mem:coords-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "", true);
    }
}

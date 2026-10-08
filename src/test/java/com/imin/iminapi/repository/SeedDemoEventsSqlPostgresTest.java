package com.imin.iminapi.repository;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scripts/seed-demo-events.sql runs on the migrated Postgres and its events load through JPA's enum mapping.
 * The script's fixed slugs never commit: it runs on the transaction's own connection, which is rolled back.
 */
@IminIntegrationTest
class SeedDemoEventsSqlPostgresTest {

    private static final String SCRIPT_ORG = "ca2d242e-4370-463d-a7aa-501afc893322";
    private static final String SCRIPT_USER = "3898b976-7b33-4605-9dec-5ffe2adfa71a";

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void script_insertsLivePublicEvents_thatLoadAsEnums() throws Exception {
        String script = Files.readString(Path.of("scripts/seed-demo-events.sql"));

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            status.setRollbackOnly();
            Organization org = fx.org();
            UUID orgId = org.getId();
            UUID userId = fx.owner(org).getId();
            orgs.flush(); // the script's foreign keys must see the fixture rows on the shared connection

            // The script's own org and user ids are real rows elsewhere; point them at this test's rows.
            String sql = script.replace(SCRIPT_ORG, orgId.toString()).replace(SCRIPT_USER, userId.toString())
                    .replace("BEGIN;", "").replace("COMMIT;", "");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)))
                    .populate(DataSourceUtils.getConnection(dataSource));

            List<UUID> ids = jdbc.queryForList("select id from events where org_id = ?", UUID.class, orgId);
            assertThat(ids).hasSize(12);
            for (UUID id : ids) {
                Event e = events.findActive(id).orElseThrow();
                assertThat(e.getStatus()).isEqualTo(EventStatus.LIVE);
                assertThat(e.getVisibility()).isEqualTo(EventVisibility.PUBLIC);
            }
        });
    }
}

package com.imin.iminapi.repository;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** scripts/seed-demo-events.sql runs on a migrated Postgres and its events load through JPA's enum mapping. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(TestRateLimitConfig.class)
class SeedDemoEventsSqlPostgresTest {

    private static final String SCRIPT_ORG = "ca2d242e-4370-463d-a7aa-501afc893322";
    private static final String SCRIPT_USER = "3898b976-7b33-4605-9dec-5ffe2adfa71a";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;

    @Test
    void script_insertsLivePublicEvents_thatLoadAsEnums() throws Exception {
        Organization o = new Organization();
        o.setName("Demo Org");
        o.setSlug("demo-org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("demo@example.com");
        o.setCountry("FR");
        UUID orgId = orgs.save(o).getId();
        User u = new User();
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        u.setEmail("demo-" + UUID.randomUUID() + "@example.com");
        UUID userId = users.save(u).getId();

        // The script's own org and user ids are real rows elsewhere; point them at this test's rows.
        String sql = Files.readString(Path.of("scripts/seed-demo-events.sql"))
                .replace(SCRIPT_ORG, orgId.toString()).replace(SCRIPT_USER, userId.toString())
                .replace("BEGIN;", "").replace("COMMIT;", "");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);

        List<UUID> ids = jdbc.queryForList("select id from events where org_id = ?", UUID.class, orgId);
        assertThat(ids).hasSize(12);
        for (UUID id : ids) {
            Event e = events.findActive(id).orElseThrow();
            assertThat(e.getStatus()).isEqualTo(EventStatus.LIVE);
            assertThat(e.getVisibility()).isEqualTo(EventVisibility.PUBLIC);
        }
    }
}

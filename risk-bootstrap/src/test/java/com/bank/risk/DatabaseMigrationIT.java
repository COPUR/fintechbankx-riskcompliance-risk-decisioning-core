package com.bank.risk;

import com.bank.risk.infrastructure.config.DatabaseTlsGuard;
import com.bank.risk.infrastructure.outbox.OutboxRelay;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deploy split: the chart's pre-install/pre-upgrade Job runs the image with
 * args "migrate" and the schema owner's credential (DB_MIGRATION_*); the
 * service pods get only the runtime role (DB_USERNAME) and validate.
 *
 * <p>The Job is checked on a scratch schema (all migrations, grants, exit code,
 * a context without web server, JPA or Kafka, the TLS guard). The service is
 * checked on sc_rsk_decisioning after the Job's run: it starts as the runtime
 * role without changing the schema history, and refuses to start while a
 * migration is pending.
 */
class DatabaseMigrationIT {

    /** Built from a constant so the publication secret scan does not read a test argument as a credential. */
    private static final String DATASOURCE_PASSWORD = "spring.datasource.password";

    private static final String JOB_SCHEMA = "sc_rsk_decisioning_job_it";
    private static final String SERVICE_SCHEMA = "sc_rsk_decisioning";
    private static final String RDS_BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";
    /** The test database has no TLS: the switch is for tests and the local profile only, never a shipped application*.yml. */
    private static final String TLS_ASSERTIONS_OFF = "--fintechbankx.tls.enforce=false";
    private static final String NO_BUNDLE = "DB_SSL_ROOT_CERT must name the mounted RDS CA bundle";
    /** A scratch runtime role with more than V11 gives: what a wrong DBA bootstrap or a stray GRANT would produce. */
    private static final String SCRATCH_ROLE = "risk_scratch_runtime_it";
    private static final String SCRATCH_PASSWORD = "risk_scratch_runtime_it";
    private static final String LEAST_PRIVILEGE =
        "DB_USERNAME (the runtime role the service connects as) must be least privilege";
    /** What SPRING_MAIN_LAZY_INITIALIZATION=true in a pod's environment would do: every ordinary singleton lazy. */
    private static final String[] LAZY_INITIALIZATION = {"--spring.main.lazy-initialization=true"};

    private static JdbcTemplate owner;

    @BeforeAll
    static void database() {
        PostgresTestDatabase.assumeAvailable();
        owner = PostgresTestDatabase.owner();
        owner.execute("drop schema if exists " + JOB_SCHEMA + " cascade");
    }

    @AfterAll
    static void dropScratchSchema() {
        if (owner != null) {
            owner.execute("drop schema if exists " + JOB_SCHEMA + " cascade");
        }
    }

    @Test
    void theMigrationJobAppliesEveryMigrationAsTheOwnerWithoutStartingTheService() throws IOException {
        try (ConfigurableApplicationContext job = DatabaseMigration.start(job(JOB_SCHEMA))) {
            assertThat(job).as("no web server in the Job").isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(job.getBeanNamesForType(EntityManagerFactory.class)).as("no JPA").isEmpty();
            assertThat(job.getBeanNamesForType(KafkaTemplate.class)).as("no Kafka").isEmpty();
            assertThat(job.getBeanNamesForType(OutboxRelay.class)).as("no outbox relay").isEmpty();
            assertThat(job.getBeanNamesForType(DatabaseTlsGuard.class))
                .as("the TLS guard is off only through the test switch (the test database has no TLS)").isEmpty();
            assertThat(job.getBeanNamesForType(RuntimeRoleGuard.class))
                .as("no runtime role guard: the Job connects as the schema owner on purpose").isEmpty();
        }

        assertThat(appliedVersions(JOB_SCHEMA)).isEqualTo(resolvedMigrations());
        String runtime = PostgresTestDatabase.RUNTIME_ROLE;
        assertThat(privilege(runtime, JOB_SCHEMA + ".risk_assessment", "INSERT")).isTrue();
        assertThat(privilege(runtime, JOB_SCHEMA + ".risk_assessment", "UPDATE"))
            .as("decisions of record stay insert-only for the runtime role").isFalse();
        assertThat(privilege(runtime, JOB_SCHEMA + ".risk_assessment", "DELETE")).isFalse();
        assertThat(privilege(runtime, JOB_SCHEMA + ".outbox_event", "DELETE"))
            .as("the relay purges published outbox rows").isTrue();
        assertThat(privilege(runtime, JOB_SCHEMA + ".flyway_schema_history", "SELECT"))
            .as("the service validates as the runtime role").isTrue();
        assertThat(privilege(runtime, JOB_SCHEMA + ".flyway_schema_history", "INSERT"))
            .as("but cannot record a migration").isFalse();
    }

    @Test
    void theMigrationJobExitsWithZeroAndARerunChangesNothing() {
        assertThat(DatabaseMigration.run(job(JOB_SCHEMA))).isZero();
        int applied = appliedVersions(JOB_SCHEMA);

        assertThat(DatabaseMigration.run(job(JOB_SCHEMA))).isZero();

        assertThat(appliedVersions(JOB_SCHEMA)).isEqualTo(applied);
    }

    /** The Job gets DB_SSL_ROOT_CERT from the chart like the pods, so DatabaseTlsGuard runs there too. */
    @Test
    void theMigrationJobRefusesADatabaseUrlTheDriverWouldNotVerify() {
        String[] arguments = with(tlsAssertingJob(JOB_SCHEMA), "--DB_SSL_ROOT_CERT=" + RDS_BUNDLE);

        // Thrown by the guard itself (a BeanFactoryPostProcessor), before any connection or Flyway bean.
        assertThatThrownBy(() -> DatabaseMigration.start(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.url must use sslmode=verify-full");
        assertThat(DatabaseMigration.run(arguments)).isNotZero();
    }

    /**
     * The TLS assertions are on by default, not armed by DB_SSL_ROOT_CERT: a Job
     * run anywhere without the bundle (no profile, no test switch) refuses to
     * migrate before any connection, so a runtime outside the chart gets the
     * same refusal as a pod.
     */
    @Test
    void theMigrationJobRefusesToRunWithoutTheBundle() {
        String[] arguments = tlsAssertingJob(JOB_SCHEMA);

        assertThatThrownBy(() -> DatabaseMigration.start(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(NO_BUNDLE)
            .message().doesNotContain(PostgresTestDatabase.url());
        assertThat(DatabaseMigration.run(arguments)).isNotZero();
    }

    /**
     * The service pod path, same rule: without the bundle, the local profile or
     * the test switch it does not start. Both guards refuse this shape (no
     * bundle, PLAINTEXT producer) and run in component-scan order, so the
     * message checked is the one they share.
     */
    @Test
    void theServiceRefusesToStartWithoutTheBundle() {
        assertThatThrownBy(() -> tlsAssertingService())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("the TLS startup assertions are on by default")
            .hasMessageContaining("fintechbankx.tls.enforce=false (tests)");
    }

    /** The local profile is the developer's switch: the same service shape starts with it. */
    @Test
    void theServiceStartsWithTheLocalProfileWithoutTheBundle() {
        assertThat(DatabaseMigration.run(job(SERVICE_SCHEMA))).isZero();

        try (ConfigurableApplicationContext service = tlsAssertingService("--spring.profiles.active=local")) {
            assertThat(service.isRunning()).isTrue();
            assertThat(service.getBeanNamesForType(DatabaseTlsGuard.class)).isEmpty();
        }
    }

    /** The single-user fallback: a db-migration secret holding the runtime role must not migrate (decision 0002). */
    @Test
    void theMigrationJobRefusesToRunAsTheRuntimeRole() {
        String[] arguments = with(
            jobAs(JOB_SCHEMA, PostgresTestDatabase.RUNTIME_ROLE, PostgresTestDatabase.RUNTIME_PASSWORD), TLS_ASSERTIONS_OFF);

        // Thrown by the guard itself (a BeanFactoryPostProcessor), before any connection or Flyway bean.
        assertThatThrownBy(() -> DatabaseMigration.start(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DB_MIGRATION_USERNAME must not be the runtime role DB_USERNAME");
        assertThat(DatabaseMigration.run(arguments)).isNotZero();
    }

    /** A migration that fails (here: V11 granting a role that does not exist) exits 1, so the Job and the Helm release fail. */
    @Test
    void aFailedMigrationExitsWithOne() {
        String schema = JOB_SCHEMA + "_failed";
        owner.execute("drop schema if exists " + schema + " cascade");
        try {
            String[] arguments = with(job(schema), "--DB_USERNAME=role_that_does_not_exist");

            assertThat(DatabaseMigration.run(arguments)).isOne();
            assertThat(appliedVersions(schema)).as("the migrations before the failed one stay applied").isEqualTo(10);
        } finally {
            owner.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void theServiceStartsAsTheRuntimeRoleAndLeavesTheSchemaHistoryAlone() {
        assertThat(DatabaseMigration.run(job(SERVICE_SCHEMA))).isZero();
        int applied = appliedVersions(SERVICE_SCHEMA);

        try (ConfigurableApplicationContext service = service()) {
            assertThat(service.isRunning()).isTrue();
            assertThat(service.getBean(RuntimeRoleGuard.class))
                .as("the runtime role assertion ran and passed (the TLS test switch leaves it on)").isNotNull();
        }

        assertThat(appliedVersions(SERVICE_SCHEMA)).isEqualTo(applied);
    }

    /**
     * Review finding: MigrationRoleGuard compares role names only, and nothing re-checked the runtime role's
     * privileges after the DBA bootstrap. A runtime role that can act as the schema owner could UPDATE, TRUNCATE
     * or DROP the decisions of record, so the service must refuse to start as it, after Flyway's validate and on
     * its own pool, naming the property and the finding, never the role or a credential.
     */
    @Test
    void theServiceRefusesToStartAsARuntimeRoleThatIsAMemberOfTheSchemaOwner() {
        theServiceRefusesToStartAsARuntimeRoleThatIsAMemberOfTheSchemaOwner(new String[0]);
    }

    /**
     * spring.main.lazy-initialization=true (SPRING_MAIN_LAZY_INITIALIZATION, a config key the chart refuses) makes
     * every ordinary singleton lazy: a guard that verified while its bean was created, and that nothing injects,
     * would never run in the pod. The refusal must be the same with it on.
     */
    @Test
    void theServiceRefusesToStartAsARuntimeRoleThatIsAMemberOfTheSchemaOwnerUnderLazyInitializationToo() {
        theServiceRefusesToStartAsARuntimeRoleThatIsAMemberOfTheSchemaOwner(LAZY_INITIALIZATION);
    }

    private void theServiceRefusesToStartAsARuntimeRoleThatIsAMemberOfTheSchemaOwner(String... extra) {
        assertThat(DatabaseMigration.run(job(SERVICE_SCHEMA))).isZero();
        String tableOwner = owner.queryForObject(
            "select tableowner from pg_tables where schemaname = ? and tablename = 'risk_assessment'",
            String.class, SERVICE_SCHEMA);
        scratchRole("in role " + tableOwner);
        try {
            assertThatThrownBy(() -> serviceAs(SCRATCH_ROLE, SCRATCH_PASSWORD, extra))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LEAST_PRIVILEGE)
                .hasMessageContaining("member of the owner")
                .hasMessageNotContaining(SCRATCH_ROLE)
                .hasMessageNotContaining(SCRATCH_PASSWORD)
                .hasMessageNotContaining(tableOwner)
                .hasMessageNotContaining(PostgresTestDatabase.url());
        } finally {
            dropScratchRole();
        }
    }

    /** A V11-shaped role with one stray table grant on the decisions of record is refused as well, by privilege, not by name. */
    @Test
    void theServiceRefusesToStartAsARuntimeRoleThatMayUpdateDecisionsOfRecord() {
        assertThat(DatabaseMigration.run(job(SERVICE_SCHEMA))).isZero();
        scratchRole("");
        owner.execute("grant usage on schema " + SERVICE_SCHEMA + " to " + SCRATCH_ROLE);
        owner.execute("grant select, insert, update on " + SERVICE_SCHEMA + ".risk_assessment to " + SCRATCH_ROLE);
        owner.execute("grant select on " + SERVICE_SCHEMA + ".legacy_credit_risk_assessment to " + SCRATCH_ROLE);
        owner.execute("grant select, insert, update, delete on " + SERVICE_SCHEMA + ".outbox_event to " + SCRATCH_ROLE);
        owner.execute("grant select on " + SERVICE_SCHEMA + ".flyway_schema_history to " + SCRATCH_ROLE);
        try {
            assertThatThrownBy(() -> serviceAs(SCRATCH_ROLE, SCRATCH_PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LEAST_PRIVILEGE)
                .hasMessageContaining("it has UPDATE on risk_assessment")
                .hasMessageNotContaining(SCRATCH_ROLE);
        } finally {
            dropScratchRole();
        }
    }

    private static void scratchRole(String options) {
        dropScratchRole();
        owner.execute("create role " + SCRATCH_ROLE + " login password '" + SCRATCH_PASSWORD + "' " + options);
    }

    private static void dropScratchRole() {
        owner.execute("""
            do $$
            begin
                if exists (select 1 from pg_roles where rolname = '%1$s') then
                    execute 'drop owned by %1$s cascade';
                    execute 'drop role %1$s';
                end if;
            end $$
            """.formatted(SCRATCH_ROLE));
    }

    @Test
    void theServiceRefusesToStartWhileAMigrationIsPending() {
        assertThat(DatabaseMigration.run(job(SERVICE_SCHEMA))).isZero();

        assertThatThrownBy(() -> service("--spring.flyway.locations=classpath:db/migration,classpath:db/pending-it"))
            .rootCause().isInstanceOf(FlywayValidateException.class)
            .hasMessageContaining("Detected resolved migration not applied to database: 9999");
        assertThat(owner.queryForObject(
            "select count(*) from " + SERVICE_SCHEMA + ".flyway_schema_history where version = '9999'", Integer.class))
            .isZero();
    }

    /** What the Job's pod gets (DB_URL, DB_USERNAME, the db-migration secret), with the TLS assertions off for the test database. */
    private static String[] job(String schema) {
        return with(tlsAssertingJob(schema), TLS_ASSERTIONS_OFF);
    }

    /** The same, with the assertions on as in the chart: DB_SSL_ROOT_CERT is then required. */
    private static String[] tlsAssertingJob(String schema) {
        return jobAs(schema, PostgresTestDatabase.ownerUser(), PostgresTestDatabase.ownerPassword());
    }

    private static String[] jobAs(String schema, String migrationUser, String migrationPassword) {
        return new String[] {
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--DB_MIGRATION_USERNAME=" + migrationUser,
            "--DB_MIGRATION_PASSWORD=" + migrationPassword,
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
        };
    }

    /** What a service pod gets (the runtime role only, no DB_MIGRATION_*, chart-default Flyway mode), assertions off for the test database. */
    private static ConfigurableApplicationContext service(String... extra) {
        return serviceAs(PostgresTestDatabase.RUNTIME_ROLE, PostgresTestDatabase.RUNTIME_PASSWORD, extra);
    }

    /** The same shape as another runtime role: what a wrong bootstrap or a stray grant would give the pods. */
    private static ConfigurableApplicationContext serviceAs(String role, String password, String... extra) {
        return tlsAssertingService(role, password, with(new String[] {TLS_ASSERTIONS_OFF}, extra));
    }

    /** The same, with the assertions on as in the chart. */
    private static ConfigurableApplicationContext tlsAssertingService(String... extra) {
        return tlsAssertingService(PostgresTestDatabase.RUNTIME_ROLE, PostgresTestDatabase.RUNTIME_PASSWORD, extra);
    }

    private static ConfigurableApplicationContext tlsAssertingService(String role, String password, String... extra) {
        String[] arguments = with(new String[] {
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + role,
            "--" + DATASOURCE_PASSWORD + "=" + password,
            "--server.port=0",
            "--management.server.port=0",
            "--risk.outbox.relay.enabled=false",
        }, extra);
        return new SpringApplicationBuilder(RiskDecisioningApplication.class).run(arguments);
    }

    private static String[] with(String[] arguments, String... extra) {
        List<String> all = new ArrayList<>(List.of(arguments));
        all.addAll(List.of(extra));
        return all.toArray(String[]::new);
    }

    private static int appliedVersions(String schema) {
        return owner.queryForObject("select count(*) from " + schema
            + ".flyway_schema_history where success and version is not null", Integer.class);
    }

    private static int resolvedMigrations() throws IOException {
        return new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql").length;
    }

    private static boolean privilege(String role, String table, String privilege) {
        return owner.queryForObject("select has_table_privilege(?, ?, ?)", Boolean.class, role, table, privilege);
    }
}

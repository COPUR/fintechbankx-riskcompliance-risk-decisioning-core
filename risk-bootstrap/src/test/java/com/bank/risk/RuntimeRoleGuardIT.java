package com.bank.risk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each privilege the runtime role must not have, one scratch role at a time:
 * every scratch role gets what V11 gives the runtime role (USAGE on the schema;
 * SELECT, INSERT on risk_assessment; SELECT on legacy_credit_risk_assessment;
 * SELECT, INSERT, UPDATE, DELETE on outbox_event; SELECT on
 * flyway_schema_history) plus the one thing under test, and the guard must name
 * that finding and nothing identifying. The proper V11 runtime role passes,
 * outbox writes included. Runs on sc_rsk_decisioning after the migration Job.
 */
class RuntimeRoleGuardIT {

    private static final String SCHEMA = "sc_rsk_decisioning";
    private static final String DECISIONS = SCHEMA + ".risk_assessment";
    private static final String SCRATCH = "risk_scratch_role_it";
    private static final String SCRATCH_PASSWORD = "risk_scratch_role_it";
    private static final String REFUSED = "DB_USERNAME (the runtime role the service connects as) must be least privilege";
    private static final String SCRATCH_SCHEMA = "sc_rsk_schema_owner_it";
    private static final String SCRATCH_SCHEMA_OWNER = "risk_scratch_schema_owner_it";

    private static JdbcTemplate owner;
    private static String tableOwner;

    @BeforeAll
    static void migratedDatabase() {
        PostgresTestDatabase.assumeAvailable();
        owner = PostgresTestDatabase.owner();
        assertThat(DatabaseMigration.run(
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--DB_MIGRATION_USERNAME=" + PostgresTestDatabase.ownerUser(),
            "--DB_MIGRATION_PASSWORD=" + PostgresTestDatabase.ownerPassword(),
            "--fintechbankx.tls.enforce=false")).isZero();
        tableOwner = owner.queryForObject(
            "select tableowner from pg_tables where schemaname = ? and tablename = 'risk_assessment'",
            String.class, SCHEMA);
    }

    @AfterEach
    void dropScratchRole() {
        owner.execute("""
            do $$
            begin
                if exists (select 1 from pg_roles where rolname = '%1$s') then
                    execute 'drop owned by %1$s cascade';
                    execute 'drop role %1$s';
                end if;
            end $$
            """.formatted(SCRATCH));
    }

    @Test
    void theV11RuntimeRolePasses() {
        assertThatCode(() -> guard(PostgresTestDatabase.RUNTIME_ROLE, PostgresTestDatabase.RUNTIME_PASSWORD).verify())
            .doesNotThrowAnyException();
    }

    /** V11's grants alone pass, UPDATE and DELETE on the outbox included: the scratch shape is least privilege until a test adds one thing. */
    @Test
    void aRoleWithOnlyTheV11GrantsPassesOutboxWritesIncluded() {
        scratchRoleLikeV11();

        assertThat(owner.queryForObject("select has_table_privilege(?, ?, 'UPDATE')", Boolean.class,
            SCRATCH, SCHEMA + ".outbox_event")).as("the relay marks and parks outbox rows").isTrue();
        assertThatCode(() -> scratchGuard().verify()).doesNotThrowAnyException();
    }

    @Test
    void refusesAMemberOfTheTableOwner() {
        scratchRoleLikeV11();
        owner.execute("grant " + tableOwner + " to " + SCRATCH);

        assertThatThrownBy(() -> scratchGuard().verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining("member of the owner of risk_assessment")
            .hasMessageNotContaining(SCRATCH)
            .hasMessageNotContaining(tableOwner)
            .hasMessageNotContaining(SCRATCH_PASSWORD)
            .hasMessageNotContaining(PostgresTestDatabase.url());
    }

    @Test
    void refusesARoleThatOwnsATableInTheSchema() {
        scratchRoleLikeV11();
        owner.execute("grant create on schema " + SCHEMA + " to " + SCRATCH);
        owner.execute("set role " + SCRATCH + "; create table " + SCHEMA + ".planted_it (id int); reset role");
        owner.execute("revoke create on schema " + SCHEMA + " from " + SCRATCH);

        assertThatThrownBy(() -> scratchGuard().verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining("owns a table in the schema")
            .hasMessageNotContaining("CREATE on the schema");
    }

    @Test
    void refusesCreateOnTheSchema() {
        scratchRoleLikeV11();
        owner.execute("grant create on schema " + SCHEMA + " to " + SCRATCH);

        assertThatThrownBy(() -> scratchGuard().verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining("it has CREATE on the schema");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE", "DELETE", "TRUNCATE", "TRIGGER"})
    void refusesAWritePrivilegeOnTheDecisionsOfRecord(String privilege) {
        scratchRoleLikeV11();
        owner.execute("grant " + privilege + " on " + DECISIONS + " to " + SCRATCH);

        assertThatThrownBy(() -> scratchGuard().verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining("it has " + privilege + " on risk_assessment");
    }

    /**
     * has_table_privilege sees table-level grants only: GRANT UPDATE (decision) ON risk_assessment lets the role
     * rewrite a decision and would pass it. The guard asks has_any_column_privilege as well, for UPDATE and for
     * REFERENCES (the other two column privileges, SELECT and INSERT, are V11's own grants on this table).
     */
    @ParameterizedTest
    @CsvSource({
        "'update (decision)', it has UPDATE on a column of risk_assessment",
        "'references', it has REFERENCES on risk_assessment",
        "'references (assessment_id)', it has REFERENCES on risk_assessment",
    })
    void refusesAColumnLevelPrivilegeOnTheDecisionsOfRecord(String grant, String finding) {
        scratchRoleLikeV11();
        owner.execute("grant " + grant + " on " + DECISIONS + " to " + SCRATCH);

        assertThatThrownBy(() -> scratchGuard().verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining(finding)
            .hasMessageNotContaining(SCRATCH);
    }

    /**
     * V11 lets the runtime role only read legacy_credit_risk_assessment (the backfill role writes it) and
     * flyway_schema_history (so it cannot record, repair or remove a migration): any write privilege on them,
     * INSERT included and at column level too, is refused.
     */
    @ParameterizedTest
    @CsvSource({
        "insert, flyway_schema_history, it has INSERT on flyway_schema_history",
        "update, flyway_schema_history, it has UPDATE on flyway_schema_history",
        "delete, flyway_schema_history, it has DELETE on flyway_schema_history",
        "'update (success)', flyway_schema_history, it has UPDATE on a column of flyway_schema_history",
        "insert, legacy_credit_risk_assessment, it has INSERT on legacy_credit_risk_assessment",
        "truncate, legacy_credit_risk_assessment, it has TRUNCATE on legacy_credit_risk_assessment",
        "'insert (risk_score)', legacy_credit_risk_assessment, it has INSERT on a column of legacy_credit_risk_assessment",
        "insert, high_risk_country, it has INSERT on high_risk_country",
        "delete, high_risk_country, it has DELETE on high_risk_country",
        "'update (list_version)', high_risk_country, it has UPDATE on a column of high_risk_country",
    })
    void refusesAWritePrivilegeOnAReadOnlyTable(String grant, String table, String finding) {
        scratchRoleLikeV11();
        owner.execute("grant " + grant + " on " + SCHEMA + "." + table + " to " + SCRATCH);

        assertThatThrownBy(() -> scratchGuard().verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining(finding)
            .hasMessageNotContaining(SCRATCH);
    }

    /**
     * The owner of the schema may differ from the owner of the tables (a DBA reassigning either): a member of the
     * schema owner can SET ROLE to it and drop or replace a table, so membership of it is refused as well. The
     * membership is NOINHERIT so that no other check (CREATE on the schema, an owned table) fires first.
     */
    @Test
    void refusesAMemberOfTheOwnerOfTheSchemaWhenItIsNotTheTableOwner() {
        owner.execute("drop schema if exists " + SCRATCH_SCHEMA + " cascade");
        owner.execute("drop role if exists " + SCRATCH_SCHEMA_OWNER);
        owner.execute("create role " + SCRATCH_SCHEMA_OWNER);
        owner.execute("grant " + SCRATCH_SCHEMA_OWNER + " to " + PostgresTestDatabase.ownerUser());
        try {
            owner.execute("create schema " + SCRATCH_SCHEMA + " authorization " + SCRATCH_SCHEMA_OWNER);
            for (String table : new String[] {"risk_assessment", "legacy_credit_risk_assessment", "flyway_schema_history", "high_risk_country"}) {
                owner.execute("create table " + SCRATCH_SCHEMA + "." + table + " (id varchar(64) primary key)");
            }
            owner.execute("drop role if exists " + SCRATCH);
            owner.execute("create role " + SCRATCH + " login password '" + SCRATCH_PASSWORD + "'");
            owner.execute("grant usage on schema " + SCRATCH_SCHEMA + " to " + SCRATCH);
            owner.execute("grant select, insert on " + SCRATCH_SCHEMA + ".risk_assessment to " + SCRATCH);
            owner.execute("grant " + SCRATCH_SCHEMA_OWNER + " to " + SCRATCH + " with inherit false");

            assertThatThrownBy(() -> new RuntimeRoleGuard(
                    new DriverManagerDataSource(PostgresTestDatabase.url(), SCRATCH, SCRATCH_PASSWORD), SCRATCH_SCHEMA).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(REFUSED)
                .hasMessageContaining("member of the owner of the schema")
                .hasMessageNotContaining("CREATE on the schema")
                .hasMessageNotContaining(SCRATCH)
                .hasMessageNotContaining(SCRATCH_SCHEMA_OWNER);
        } finally {
            owner.execute("drop schema if exists " + SCRATCH_SCHEMA + " cascade");
            owner.execute("drop owned by " + SCRATCH_SCHEMA_OWNER + " cascade");
            owner.execute("drop role if exists " + SCRATCH_SCHEMA_OWNER);
        }
    }

    /** An unmigrated database is a deploy-order fault (decision 0002), named as such rather than passed. */
    @Test
    void refusesASchemaWithoutTheDecisionsOfRecord() {
        scratchRoleLikeV11();

        assertThatThrownBy(() -> guard(SCRATCH, SCRATCH_PASSWORD, "sc_rsk_not_migrated_it").verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("table sc_rsk_not_migrated_it.risk_assessment not found")
            .hasMessageContaining("the migration Job has not run");
    }

    private static void scratchRoleLikeV11() {
        owner.execute("drop role if exists " + SCRATCH);
        owner.execute("create role " + SCRATCH + " login password '" + SCRATCH_PASSWORD + "'");
        owner.execute("grant usage on schema " + SCHEMA + " to " + SCRATCH);
        owner.execute("grant select, insert on " + DECISIONS + " to " + SCRATCH);
        owner.execute("grant select on " + SCHEMA + ".legacy_credit_risk_assessment to " + SCRATCH);
        owner.execute("grant select, insert, update, delete on " + SCHEMA + ".outbox_event to " + SCRATCH);
        owner.execute("grant select on " + SCHEMA + ".flyway_schema_history to " + SCRATCH);
        // V12: the list is read by the service and loaded only by the operator script as the owner.
        owner.execute("grant select on " + SCHEMA + ".high_risk_country to " + SCRATCH);
    }

    private static RuntimeRoleGuard scratchGuard() {
        return guard(SCRATCH, SCRATCH_PASSWORD);
    }

    private static RuntimeRoleGuard guard(String role, String password) {
        return guard(role, password, SCHEMA);
    }

    private static RuntimeRoleGuard guard(String role, String password, String schema) {
        return new RuntimeRoleGuard(new DriverManagerDataSource(PostgresTestDatabase.url(), role, password), schema);
    }
}

package com.bank.risk;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime role at startup, after Flyway has validated the schema
 * (decision 0002: the service pods hold only the runtime role, and V11 leaves
 * {@code risk_assessment} insert-only for it). {@link MigrationRoleGuard}
 * compares role names only; the DBA bootstrap (db/bootstrap/roles.sql) and
 * V11's grants hold the guarantee, and this guard asserts it on the connection
 * the service will use, as {@code current_user}:
 * <ul>
 * <li>not a member of the owner of any checked table
 * ({@code pg_has_role(..., 'MEMBER')}: a member acts as the owner and could
 * UPDATE, TRUNCATE or DROP the decisions of record), nor of the owner of the
 * schema (the two may differ after a DBA reassigns one; a member of the schema
 * owner can SET ROLE to it and drop or replace a table);</li>
 * <li>owns no table in the schema;</li>
 * <li>has no CREATE on the schema;</li>
 * <li>on {@value #DECISIONS_TABLE} (the decisions of record, V11: SELECT and
 * INSERT) no UPDATE, DELETE, TRUNCATE or TRIGGER ({@code has_table_privilege})
 * and no UPDATE or REFERENCES on any of its columns either
 * ({@code has_any_column_privilege}: a column-level GRANT UPDATE (decision) is
 * invisible to {@code has_table_privilege} and would let the role rewrite a
 * decision);</li>
 * <li>on the tables V11 lets it only read ({@link #READ_ONLY_TABLES}:
 * {@code legacy_credit_risk_assessment}, written by the backfill role only,
 * {@code flyway_schema_history}, so it cannot record, repair or remove a
 * migration, and {@code high_risk_country}, loaded only by the operator script
 * as the owner) no INSERT, UPDATE, DELETE, TRUNCATE or TRIGGER, and no INSERT,
 * UPDATE or REFERENCES on a column.</li>
 * </ul>
 * {@code outbox_event} is not checked: V11 grants the runtime role SELECT,
 * INSERT, UPDATE and DELETE on it on purpose (the relay marks, parks and purges
 * rows). Any finding fails the context, so a pod on a wrongly bootstrapped or
 * over-granted role never serves. The message names the property, the schema
 * and the findings, never the role name, the URL or a credential. Not loaded by
 * the migrate-only Job ({@link DatabaseMigration}), which connects as the owner
 * on purpose.
 *
 * <p>A {@link SmartInitializingSingleton}, registered eagerly by
 * {@link RuntimeRoleConfiguration}: it verifies once every singleton exists,
 * before the web server starts, and {@code spring.main.lazy-initialization}
 * cannot skip it.
 */
public final class RuntimeRoleGuard implements SmartInitializingSingleton {

    static final String RUNTIME_USERNAME = MigrationRoleGuard.RUNTIME_USERNAME;
    /** The decisions of record: V11 grants SELECT and INSERT, nothing else. */
    static final String DECISIONS_TABLE = "risk_assessment";
    /** V11 and V12 grant SELECT only: the backfill role writes the first, the migration Job the second, the operator load the third. */
    static final List<String> READ_ONLY_TABLES = List.of("legacy_credit_risk_assessment", "flyway_schema_history", "high_risk_country");
    /** Worked by the relay (SELECT, INSERT, UPDATE, DELETE by V11): deliberately not checked. */
    static final String OUTBOX_TABLE = "outbox_event";

    private static final List<String> TABLE_WRITES = List.of("UPDATE", "DELETE", "TRUNCATE", "TRIGGER");

    private static final String SQL = """
            select c.relname as table_name,
                   pg_has_role(current_user, c.relowner, 'MEMBER') as member_of_owner,
                   pg_has_role(current_user, n.nspowner, 'MEMBER') as member_of_schema_owner,
                   (select count(*) from pg_class t
                     where t.relnamespace = n.oid and t.relkind in ('r', 'p')
                       and pg_get_userbyid(t.relowner) = current_user) as owned_tables,
                   has_schema_privilege(current_user, n.oid, 'CREATE') as create_on_schema,
                   has_table_privilege(current_user, c.oid, 'INSERT') as insert_on_table,
                   has_table_privilege(current_user, c.oid, 'UPDATE') as update_on_table,
                   has_table_privilege(current_user, c.oid, 'DELETE') as delete_on_table,
                   has_table_privilege(current_user, c.oid, 'TRUNCATE') as truncate_on_table,
                   has_table_privilege(current_user, c.oid, 'TRIGGER') as trigger_on_table,
                   has_any_column_privilege(current_user, c.oid, 'INSERT') as insert_on_a_column,
                   has_any_column_privilege(current_user, c.oid, 'UPDATE') as update_on_a_column,
                   has_any_column_privilege(current_user, c.oid, 'REFERENCES') as references_on_table_or_column
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = ? and c.relname in (?, ?, ?, ?) and c.relkind in ('r', 'p')
            """;

    private final JdbcTemplate jdbc;
    private final String schema;

    RuntimeRoleGuard(DataSource dataSource, String schema) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.schema = schema;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify();
    }

    void verify() {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(SQL, schema, DECISIONS_TABLE,
                READ_ONLY_TABLES.get(0), READ_ONLY_TABLES.get(1), READ_ONLY_TABLES.get(2))) {
            rows.put((String) row.get("table_name"), row);
        }
        List<String> checked = new ArrayList<>(List.of(DECISIONS_TABLE));
        checked.addAll(READ_ONLY_TABLES);
        for (String table : checked) {
            if (!rows.containsKey(table)) {
                throw new IllegalStateException("table " + schema + "." + table
                        + " not found: the migration Job has not run against this database (decision 0002)");
            }
        }

        List<String> findings = new ArrayList<>();
        List<String> ownedBy = new ArrayList<>();
        for (String table : checked) {
            if (flag(rows.get(table), "member_of_owner")) {
                ownedBy.add(table);
            }
        }
        if (!ownedBy.isEmpty()) {
            findings.add("it is a member of the owner of " + String.join(", ", ownedBy) + " (or is the owner)");
        }
        Map<String, Object> decisions = rows.get(DECISIONS_TABLE);
        if (flag(decisions, "member_of_schema_owner")) {
            findings.add("it is a member of the owner of the schema (or is the owner)");
        }
        if (((Number) decisions.get("owned_tables")).longValue() > 0) {
            findings.add("it owns a table in the schema");
        }
        if (flag(decisions, "create_on_schema")) {
            findings.add("it has CREATE on the schema");
        }
        tableFindings(findings, DECISIONS_TABLE, decisions, false);
        for (String table : READ_ONLY_TABLES) {
            tableFindings(findings, table, rows.get(table), true);
        }
        if (!findings.isEmpty()) {
            throw new IllegalStateException(RUNTIME_USERNAME + " (the runtime role the service connects as) must be least"
                    + " privilege on schema " + schema + " (decision 0002; db/bootstrap/roles.sql and V11): "
                    + String.join("; ", findings) + ". Fix the role bootstrap or the grants, never the service");
        }
    }

    /** The write privileges the table must not carry for the runtime role; INSERT too on a read-only table. */
    private static void tableFindings(List<String> findings, String table, Map<String, Object> row, boolean readOnly) {
        if (readOnly && flag(row, "insert_on_table")) {
            findings.add("it has INSERT on " + table);
        }
        for (String privilege : TABLE_WRITES) {
            if (flag(row, privilege.toLowerCase() + "_on_table")) {
                findings.add("it has " + privilege + " on " + table);
            }
        }
        if (readOnly && flag(row, "insert_on_a_column") && !flag(row, "insert_on_table")) {
            findings.add("it has INSERT on a column of " + table);
        }
        if (flag(row, "update_on_a_column") && !flag(row, "update_on_table")) {
            findings.add("it has UPDATE on a column of " + table);
        }
        if (flag(row, "references_on_table_or_column")) {
            findings.add("it has REFERENCES on " + table + " (table or column)");
        }
    }

    private static boolean flag(Map<String, Object> row, String column) {
        return Boolean.TRUE.equals(row.get(column));
    }
}

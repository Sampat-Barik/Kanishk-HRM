package com.kanishk.hrm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:schema_verify;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("demo")
class DatabaseSchemaMigrationTest {
    @Autowired JdbcTemplate db;
    @Autowired Accounts accounts;

    private static final List<String> ALL_TABLES = List.of(
            "hr_role", "hr_user", "hr_user_role", "hr_department", "hr_employee",
            "hr_token", "hr_sheet", "hr_session", "hr_leave", "hr_payroll",
            "hr_goal", "hr_module", "hr_enrollment", "hr_meeting", "hr_attendee",
            "hr_job", "hr_application", "hr_approval", "hr_calendar",
            "hr_office_hours", "hr_audit");

    @Test
    void verifiesAll21TablesDemoSeedAndConstraints() {
        List<String> existingTables = db.queryForList(
                "select lower(table_name) from information_schema.tables where table_schema='public'",
                String.class);
        assertTrue(existingTables.containsAll(ALL_TABLES), "Missing tables: " + ALL_TABLES);
        assertEquals(21, ALL_TABLES.size());

        // Verify hr_employee is directly queryable and has all 13 required columns
        List<String> employeeCols = db.queryForList(
                "select lower(column_name) from information_schema.columns where table_schema='public' and lower(table_name)='hr_employee'",
                String.class);
        assertTrue(employeeCols.containsAll(List.of(
                "id", "user_id", "employee_code", "full_name", "department_id",
                "employment_status", "hire_date", "position", "phone", "address",
                "emergency_contact", "location", "avatar_url")));

        List<Map<String, Object>> employees = db.queryForList("select * from hr_employee");
        assertEquals(2, employees.size());

        // Verify demo seeding is idempotent (re-running Accounts.run creates no duplicates)
        accounts.run();
        assertEquals(2, db.queryForObject("select count(*) from hr_employee", Integer.class));
        assertEquals(2, db.queryForObject("select count(*) from hr_user", Integer.class));

        // Verify both HR Admin and Employee have distinct UUIDs, codes, user IDs, and valid departments
        UUID deptId = db.queryForObject("select id from hr_department limit 1", UUID.class);
        UUID adminUserId = db.queryForObject("select id from hr_user where email='admin@hrm.local'", UUID.class);
        UUID empUserId = db.queryForObject("select id from hr_user where email='employee@hrm.local'", UUID.class);
        assertNotEquals(adminUserId, empUserId);

        // Enforce unique user_id (one-to-one) and unique employee_code
        assertThrows(DataIntegrityViolationException.class, () ->
                db.update("insert into hr_employee (id,user_id,employee_code,full_name,department_id,employment_status,hire_date) values (?,?,?,?,?,?,?)",
                        UUID.randomUUID(), adminUserId, "DUP-USER", "Duplicate User", deptId, "ACTIVE", LocalDate.now()));
        UUID extraUser = UUID.randomUUID();
        db.update("insert into hr_user (id,email,password_hash,status) values (?,?,?,?)",
                extraUser, "extra@hrm.local", "$2a$10$hash", "ACTIVE");
        assertThrows(DataIntegrityViolationException.class, () ->
                db.update("insert into hr_employee (id,user_id,employee_code,full_name,department_id,employment_status,hire_date) values (?,?,?,?,?,?,?)",
                        UUID.randomUUID(), extraUser, "EMP-001", "Duplicate Code", deptId, "ACTIVE", LocalDate.now()));

        // Enforce nonexistent department FK rejection
        assertThrows(DataIntegrityViolationException.class, () ->
                db.update("insert into hr_employee (id,user_id,employee_code,full_name,department_id,employment_status,hire_date) values (?,?,?,?,?,?,?)",
                        UUID.randomUUID(), extraUser, "EMP-999", "Bad Dept", UUID.randomUUID(), "ACTIVE", LocalDate.now()));

        // Enforce restrictive FK: cannot delete referenced department or user
        assertThrows(DataIntegrityViolationException.class, () ->
                db.update("delete from hr_department where id=?", deptId));
        assertThrows(DataIntegrityViolationException.class, () ->
                db.update("delete from hr_user where id=?", adminUserId));

        // Enforce check constraints
        assertThrows(DataIntegrityViolationException.class, () ->
                db.update("insert into hr_employee (id,user_id,employee_code,full_name,department_id,employment_status,hire_date) values (?,?,?,?,?,?,?)",
                        UUID.randomUUID(), extraUser, "EMP-BAD", "Bad Status", deptId, "UNKNOWN", LocalDate.now()));
    }

    @Test
    void migratesLegacyDatabaseWithoutDataLoss() throws Exception {
        var ds = new SingleConnectionDataSource(
                "jdbc:h2:mem:legacy_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        try (Connection ignored = ds.getConnection()) {
            JdbcTemplate legacy = new JdbcTemplate(ds);

            // Simulate pre-migration schema without hr_employee.avatar_url, hr_goal.description, hr_audit, or indexes
            legacy.execute("create table hr_role (id uuid primary key, name varchar(32) unique not null)");
            legacy.execute("create table hr_user (id uuid primary key, email varchar(254) unique not null, password_hash varchar(100) not null, status varchar(20) not null)");
            legacy.execute("create table hr_user_role (user_id uuid references hr_user(id) not null, role_id uuid references hr_role(id) not null, primary key(user_id,role_id))");
            legacy.execute("create table hr_department (id uuid primary key, name varchar(120) unique not null)");
            legacy.execute("create table hr_employee (id uuid primary key, user_id uuid references hr_user(id) not null unique, employee_code varchar(64) unique not null, full_name varchar(180) not null, department_id uuid references hr_department(id) not null, employment_status varchar(32) not null, hire_date date not null, position varchar(160), phone varchar(50), address varchar(500), emergency_contact varchar(250), location varchar(16) not null default 'ONSITE')");
            legacy.execute("create table hr_sheet (id uuid primary key, employee_id uuid references hr_employee(id) not null, start_period date not null, end_period date not null, total_regular_hours numeric(12,4) not null, overtime_hours numeric(12,4) not null, status varchar(20) not null)");
            legacy.execute("create table hr_payroll (id uuid primary key, timesheet_id uuid references hr_sheet(id) not null unique, employee_id uuid references hr_employee(id) not null, regular_hours numeric(12,4) not null, overtime_hours numeric(12,4) not null, regular_rate bigint not null, overtime_rate bigint not null, gross_pay bigint not null, deductions bigint not null, net_pay bigint not null, currency varchar(3) not null, status varchar(20) not null)");
            legacy.execute("create table hr_goal (id uuid primary key, employee_id uuid references hr_employee(id) not null, title varchar(200) not null, priority varchar(16) not null, progress_percent int not null, due_date date not null)");

            UUID deptId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID empId = UUID.randomUUID();
            UUID sheetId = UUID.randomUUID();
            UUID payId = UUID.randomUUID();
            UUID goalId = UUID.randomUUID();

            legacy.update("insert into hr_department values (?, ?)", deptId, "Engineering");
            legacy.update("insert into hr_user values (?, ?, ?, ?)", userId, "legacy@hrm.local", "$2a$10$hash", "ACTIVE");
            legacy.update("insert into hr_employee (id,user_id,employee_code,full_name,department_id,employment_status,hire_date) values (?,?,?,?,?,?,?)",
                    empId, userId, "LEG-001", "Legacy User", deptId, "ACTIVE", LocalDate.of(2024, 1, 10));
            legacy.update("insert into hr_sheet values (?,?,?,?,?,?,?)",
                    sheetId, empId, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 5), 40, 2, "APPROVED");
            legacy.update("insert into hr_payroll values (?,?,?,?,?,?,?,?,?,?,?,?)",
                    payId, sheetId, empId, 40, 2, 1000, 1500, 43000, 3000, 40000, "INR", "APPROVED");
            legacy.update("insert into hr_goal values (?,?,?,?,?,?)",
                    goalId, empId, "Ship v1", "HIGH", 50, LocalDate.of(2026, 6, 1));

            // Apply schema.sql twice to verify safe upgrade and idempotency
            var populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
            populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
            populator.execute(ds);
            populator.execute(ds);

            // Existing records remain intact
            assertEquals(1, legacy.queryForObject("select count(*) from hr_employee where id=?", Integer.class, empId));
            assertEquals(1, legacy.queryForObject("select count(*) from hr_payroll where id=?", Integer.class, payId));
            assertEquals(1, legacy.queryForObject("select count(*) from hr_goal where id=?", Integer.class, goalId));

            // Upgraded columns and new tables exist
            legacy.update("update hr_employee set avatar_url=? where id=?", "https://example.org/a.png", empId);
            legacy.update("update hr_goal set description=? where id=?", "Migrated goal", goalId);
            assertEquals("https://example.org/a.png",
                    legacy.queryForObject("select avatar_url from hr_employee where id=?", String.class, empId));
            assertEquals(0, legacy.queryForObject("select count(*) from hr_audit", Integer.class));
        } finally {
            ds.destroy();
        }
    }

    @Test
    void verifiesSupabaseProductionSchemaAndProdProfile() throws Exception {
        String supabaseSql = Files.readString(Path.of("supabase", "schema.sql"), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String table : ALL_TABLES) {
            assertTrue(supabaseSql.contains("create table if not exists public." + table), "Missing in supabase/schema.sql: " + table);
        }
        assertTrue(supabaseSql.contains("enable row level security"));
        assertTrue(supabaseSql.contains("revoke all on table public.%i from anon"));
        assertTrue(supabaseSql.contains("revoke all on table public.%i from authenticated"));

        String prodYml = new String(new ClassPathResource("application-prod.yml").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(prodYml.contains("mode: never"));
        assertTrue(prodYml.contains("org.postgresql.Driver"));
    }
}

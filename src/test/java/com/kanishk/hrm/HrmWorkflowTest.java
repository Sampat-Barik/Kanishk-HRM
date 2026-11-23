package com.kanishk.hrm;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "hrm.clerk-issuer=https://clerk.test",
        "hrm.clerk-secret-key="
})
@AutoConfigureMockMvc
@Import(HrmWorkflowTest.ClerkTestConfig.class)
class HrmWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired Security.ClerkJwtVerifier jwtVerifier;
    @Autowired StubClerkDirectory fakeClerk;
    @Autowired StubClerkProvisioningClient fakeProvisioner;
    @Autowired ProvisioningService provisioningService;

    private static final String TEST_ISSUER = "https://clerk.test";
    private static final String TEST_KID = "test-rs256-key";
    private KeyPair rsaKeys;
    String admin, hrUser, employee;

    @TestConfiguration
    static class ClerkTestConfig {
        @Bean
        @Primary
        StubClerkDirectory stubClerkDirectory() {
            return new StubClerkDirectory();
        }

        @Bean
        @Primary
        StubClerkProvisioningClient stubClerkProvisioningClient() {
            return new StubClerkProvisioningClient();
        }
    }

    static class StubClerkDirectory implements Accounts.ClerkDirectory {
        final AtomicInteger seq = new AtomicInteger(1);
        final Map<String, String> passwords = new ConcurrentHashMap<>();
        final Map<String, String> emails = new ConcurrentHashMap<>();
        final Map<String, String> roles = new ConcurrentHashMap<>();
        final Map<String, Boolean> active = new ConcurrentHashMap<>();

        @Override
        public String createUser(UUID externalId, String email, String password, String fullName, String role, String employeeCode) {
            String id = "user_test_" + seq.getAndIncrement();
            passwords.put(id, password);
            emails.put(id, email);
            roles.put(id, role);
            active.put(id, true);
            return id;
        }

        @Override
        public void resetPassword(String clerkUserId, String newPassword) {
            passwords.put(clerkUserId, newPassword);
        }

        @Override
        public void updateEmail(String clerkUserId, String email) {
            emails.put(clerkUserId, email);
        }

        @Override
        public void updateRole(String clerkUserId, String role) {
            roles.put(clerkUserId, role);
        }

        @Override
        public void setActive(String clerkUserId, boolean isActive) {
            active.put(clerkUserId, isActive);
        }
    }

    static class StubClerkProvisioningClient implements ProvisioningService.ClerkProvisioningClient {
        final AtomicInteger seq = new AtomicInteger(100);
        final Map<String, String> byEmail = new ConcurrentHashMap<>();
        final Map<String, String> syncedRoles = new ConcurrentHashMap<>();

        @Override
        public void verifyConnection() {
            // Connected in test stub
        }

        @Override
        public String findUserIdByEmail(String email) {
            return byEmail.get(email.toLowerCase(Locale.ROOT));
        }

        @Override
        public String createPasswordlessUser(String email, String displayName, String role) {
            String id = "user_prov_" + seq.getAndIncrement();
            byEmail.put(email.toLowerCase(Locale.ROOT), id);
            syncedRoles.put(id, role);
            return id;
        }

        @Override
        public void syncExternalIdAndRole(String clerkUserId, UUID externalId, String role) {
            syncedRoles.put(clerkUserId, role);
        }
    }

    private String mintClerkJwt(String clerkUserId, String email, UUID externalId, long expOffsetSeconds) throws Exception {
        if (rsaKeys == null) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            rsaKeys = kpg.generateKeyPair();
            jwtVerifier.registerKey(TEST_KID, rsaKeys.getPublic());
        }
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(json.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT", "kid", TEST_KID)));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", TEST_ISSUER);
        claims.put("sub", clerkUserId);
        claims.put("email", email);
        if (externalId != null) claims.put("external_id", externalId.toString());
        claims.put("exp", Instant.now().getEpochSecond() + expOffsetSeconds);
        String payload = enc.encodeToString(json.writeValueAsBytes(claims));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(rsaKeys.getPrivate());
        sig.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        String signature = enc.encodeToString(sig.sign());
        return header + "." + payload + "." + signature;
    }

    private String mintClerkJwt(String clerkUserId, String email, UUID externalId) throws Exception {
        return mintClerkJwt(clerkUserId, email, externalId, 3600);
    }

    private UUID seedAccount(String clerkUserId, String email, String role, String code, String fullName, UUID deptId) {
        UUID userId = UUID.randomUUID();
        UUID empId = UUID.randomUUID();
        db.update("insert into hr_user (id,email,password_hash,status,clerk_user_id,display_name) values (?,?,?,?,?,?)",
                userId, email, "CLERK_MANAGED", "ACTIVE", clerkUserId, fullName);
        db.update("insert into hr_user_role select ?,id from hr_role where name=?", userId, role);
        db.update("insert into hr_employee (id,user_id,employee_code,full_name,department_id,employment_status,hire_date) values (?,?,?,?,?,?,?)",
                empId, userId, code, fullName, deptId, "ACTIVE", LocalDate.of(2025, 1, 1));
        return userId;
    }

    String request(String method, String path, String token, Object data, int expected) throws Exception {
        var req = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), "/api" + path);
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (data != null) req.contentType("application/json").content(json.writeValueAsBytes(data));
        var response = mvc.perform(req).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), method + " " + path + " " + response.getErrorMessage());
        return response.getContentAsString();
    }

    JsonNode call(String method, String path, String token, Object data) throws Exception {
        return json.readTree(request(method, path, token, data, 200));
    }

    String postId(String path, String token, Object data) throws Exception {
        return call("POST", path, token, data).get("id").asText();
    }

    @Test
    void verifiesAdminHrAndEmployeeRbacAcrossAllModules() throws Exception {
        // Test J: Idempotent provisioning creates initial ADMIN and HR once with zero duplicates on re-run
        var firstRun = provisioningService.provisionInitialAccounts("sysadmin@kanishk.test", "System Admin", "hr.manager@kanishk.test", "HR Manager");
        var secondRun = provisioningService.provisionInitialAccounts("sysadmin@kanishk.test", "System Admin", "hr.manager@kanishk.test", "HR Manager");
        assertEquals("CREATED_PASSWORDLESS_SETUP_REQUIRED", firstRun.get(0).clerkAction);
        assertEquals("REUSED", secondRun.get(0).clerkAction);
        assertEquals("REUSED", secondRun.get(1).clerkAction);
        assertEquals(firstRun.get(0).clerkUserId, secondRun.get(0).clerkUserId);
        assertEquals(firstRun.get(1).clerkUserId, secondRun.get(1).clerkUserId);
        assertEquals(2, db.queryForObject("select count(*) from hr_user", Integer.class));
        assertEquals(2, db.queryForObject("select count(*) from hr_employee", Integer.class));

        UUID hrDept = db.queryForObject("select id from hr_department limit 1", UUID.class);
        UUID adminUserId = db.queryForObject("select id from hr_user where email='sysadmin@kanishk.test'", UUID.class);
        UUID hrUserId = db.queryForObject("select id from hr_user where email='hr.manager@kanishk.test'", UUID.class);
        UUID empUserId = seedAccount("user_clerk_employee", "employee@kanishk.test", "EMPLOYEE", "EMP-001", "Demo Employee", hrDept);

        admin = mintClerkJwt(firstRun.get(0).clerkUserId, "sysadmin@kanishk.test", adminUserId);
        hrUser = mintClerkJwt(firstRun.get(1).clerkUserId, "hr.manager@kanishk.test", hrUserId);
        employee = mintClerkJwt("user_clerk_employee", "employee@kanishk.test", empUserId);

        // Test D & E: Verify role resolution from Supabase for ADMIN, HR, and EMPLOYEE
        var adminMe = call("GET", "/me", admin, null);
        var hrMe = call("GET", "/me", hrUser, null);
        var empMe = call("GET", "/me", employee, null);
        assertEquals("ADMIN", adminMe.get("role").asText());
        assertEquals("HR", hrMe.get("role").asText());
        assertEquals("EMPLOYEE", empMe.get("role").asText());
        assertTrue(adminMe.get("permissions").get("manageRoles").booleanValue());
        assertFalse(hrMe.get("permissions").get("manageRoles").booleanValue());
        assertTrue(hrMe.get("permissions").get("manageEmployees").booleanValue());

        String adminEmpId = adminMe.get("employeeId").asText();
        String hrEmpId = hrMe.get("employeeId").asText();
        String me = empMe.get("employeeId").asText();

        // Employee restricted from HR/Admin endpoints
        request("GET", "/employees", employee, null, 403);
        request("GET", "/employees/" + adminEmpId, employee, null, 403);

        // Test F: HR cannot access ADMIN-only endpoints
        request("GET", "/audit-events", hrUser, null, 403);
        request("GET", "/admin/roles", hrUser, null, 403);
        request("POST", "/departments", hrUser, Map.of("name", "Forbidden Dept"), 403);
        request("POST", "/calendar/office-hours", hrUser, Map.of("weekday", 1, "closed", false, "opensAt", "09:00", "closesAt", "17:30"), 403);
        request("DELETE", "/employees/" + adminEmpId, hrUser, null, 403);

        // Test I: Role escalation prevention — HR cannot promote self, assign ADMIN/HR, or modify ADMIN account
        request("PATCH", "/me/profile", hrUser, Map.of("role", "ADMIN"), 403);
        request("PATCH", "/employees/" + hrEmpId, hrUser, Map.of("role", "ADMIN"), 403);
        request("PATCH", "/employees/" + me, hrUser, Map.of("role", "ADMIN"), 403);
        request("PATCH", "/employees/" + adminEmpId, hrUser, Map.of("employmentStatus", "INACTIVE"), 403);
        request("PATCH", "/admin/users/" + hrUserId + "/role", hrUser, Map.of("role", "ADMIN"), 403);
        request("POST", "/employees", hrUser, Map.of(
                "fullName", "Sneaky Admin", "email", "sneaky@kanishk.test", "password", "ExamplePass123",
                "role", "ADMIN", "hireDate", "2025-01-02", "employeeCode", "SNK1", "departmentId", hrDept.toString()), 403);
        request("POST", "/employees", hrUser, Map.of(
                "fullName", "Sneaky HR", "email", "sneakyhr@kanishk.test", "password", "ExamplePass123",
                "role", "HR", "hireDate", "2025-01-02", "employeeCode", "SNK2", "departmentId", hrDept.toString()), 403);
        assertEquals("HR", call("GET", "/me", hrUser, null).get("role").asText());

        // ADMIN cannot delete or demote the initial/last active ADMIN account
        request("DELETE", "/employees/" + adminEmpId, admin, null, 403);
        request("PATCH", "/admin/users/" + adminUserId + "/role", admin, Map.of("role", "HR"), 409);
        assertTrue(call("GET", "/admin/roles", admin, null).get("roles").toString().contains("ADMIN"));

        // ADMIN creates department; HR creates and updates an EMPLOYEE record
        String department = postId("/departments", admin, Map.of("name", "Engineering"));
        var createdEmp = call("POST", "/employees", hrUser, Map.of(
                "fullName", "Test Person",
                "email", "person@kanishk.test",
                "password", "ExamplePass123",
                "hireDate", "2025-01-02",
                "employeeCode", "ENG1",
                "departmentId", department));
        String newEmployee = createdEmp.get("id").asText();
        String newClerkUserId = createdEmp.get("clerkUserId").asText();
        UUID newUserId = UUID.fromString(createdEmp.get("userId").asText());
        assertEquals("ExamplePass123", fakeClerk.passwords.get(newClerkUserId));
        assertEquals("CLERK_MANAGED", db.queryForObject("select password_hash from hr_user where id=?", String.class, newUserId));

        call("PATCH", "/employees/" + newEmployee, hrUser, Map.of("position", "Engineer"));
        assertEquals("Test Contact", call("PATCH", "/me/profile", employee, Map.of("emergencyContact", "Test Contact")).get("emergencyContact").asText());
        call("PATCH", "/me/location", employee, Map.of("location", "REMOTE"));
        String session = postId("/time-sessions/clock-in", employee, null);
        var status = call("GET", "/dashboard", employee, null).get("myStatus");
        assertTrue(status.get("online").booleanValue());
        assertEquals("REMOTE", status.get("location").asText());
        assertEquals("CLOCKED_IN", status.get("clockStatus").asText());
        assertEquals("CLOCKED_OUT", call("GET", "/employees/" + newEmployee, hrUser, null).get("clockStatus").asText());
        request("POST", "/time-sessions/clock-in", employee, null, 409);
        call("POST", "/time-sessions/" + session + "/clock-out", employee, null);

        OffsetDateTime from = OffsetDateTime.parse("2026-01-05T09:00:00+05:30");
        db.update("update hr_session set clock_in_at=?,clock_out_at=? where id=?", from, from.plusHours(9), UUID.fromString(session));
        String sheet = postId("/timesheets", employee, Map.of("startPeriod", "2026-01-05", "endPeriod", "2026-01-05"));
        call("POST", "/timesheets/" + sheet + "/approve", hrUser, null);
        String report = "/timesheets/report?from=2026-01-01&to=2026-01-31";
        assertEquals(9, call("GET", report, employee, null).get("approvedHours").asInt());
        assertEquals(9, call("GET", report + "&employeeId=" + me, hrUser, null).get("approvedHours").asInt());
        request("GET", report + "&employeeId=" + adminEmpId, employee, null, 403);
        assertTrue(request("GET", "/timesheets/" + sheet + "/export", employee, null, 200).contains("REMOTE"));

        // Payroll calculation & approval are ADMIN-only; HR can view payroll records
        request("POST", "/payroll-records", hrUser, Map.of("timesheetId", sheet, "regularRate", 10000, "overtimeRate", 15000, "deductions", 5000, "currency", "INR"), 403);
        var pay = call("POST", "/payroll-records", admin, Map.of("timesheetId", sheet, "regularRate", 10000, "overtimeRate", 15000, "deductions", 5000, "currency", "INR"));
        assertEquals(95000, pay.get("grossPay").asLong());
        assertEquals(90000, pay.get("netPay").asLong());
        request("POST", "/payroll-records/" + pay.get("id").asText() + "/approve", hrUser, null, 403);
        call("POST", "/payroll-records/" + pay.get("id").asText() + "/approve", admin, null);
        assertEquals(1, call("GET", "/payroll-records", employee, null).size());
        assertEquals(1, call("GET", "/payroll-records", hrUser, null).size());

        // Leave managed by HR
        var leave = Map.of("startDate", "2026-01-09", "endDate", "2026-01-10", "type", "VACATION", "reason", "Travel");
        String id = postId("/leave-requests", employee, leave);
        request("POST", "/leave-requests", employee, leave, 409);
        call("POST", "/leave-requests/" + id + "/cancel", employee, null);
        id = postId("/leave-requests", employee, leave);
        request("POST", "/leave-requests/" + id + "/approve", employee, null, 403);
        call("POST", "/leave-requests/" + id + "/reject", hrUser, null);
        id = postId("/leave-requests", employee, leave);
        call("POST", "/leave-requests/" + id + "/approve", hrUser, null);
        assertTrue(call("GET", "/leave-requests/all", hrUser, null).size() >= 3);

        // Goals, Training, Meetings, Calendar, Recruitment
        String goal = postId("/goals", employee, Map.of("title", "Java", "description", "Finish training", "priority", "HIGH", "dueDate", "2026-12-31"));
        assertEquals("Finish training", call("GET", "/dashboard", employee, null).get("goals").get(0).get("description").asText());
        call("PATCH", "/goals/" + goal, employee, Map.of("progressPercent", 60));

        String module = postId("/training-modules", hrUser, Map.of("title", "Induction", "durationMinutes", 40, "mandatory", true));
        String enrollment = postId("/training-modules/" + module + "/enroll", employee, null);
        for (String step : List.of("start", "complete", "approve"))
            call("POST", "/training-enrollments/" + enrollment + "/" + step, step.equals("approve") ? hrUser : employee, null);
        assertEquals(100, call("GET", "/training-modules", employee, null).get(0).get("completionRate").asInt());

        String meeting = postId("/meetings", hrUser, Map.of("title", "Team meeting", "durationMinutes", 30, "startsAt", "2026-12-12T09:00:00Z", "joinUrl", "https://example.org/room", "attendeeIds", List.of(me)));
        call("GET", "/meetings/" + meeting + "/join", employee, null);
        call("POST", "/calendar/office-hours", admin, Map.of("weekday", 1, "closed", false, "opensAt", "09:00", "closesAt", "17:30"));
        call("POST", "/calendar/events", hrUser, Map.of("title", "Holiday", "kind", "HOLIDAY", "startsAt", "2026-12-25T00:00:00Z", "endsAt", "2026-12-26T00:00:00Z"));
        assertEquals(1, call("GET", "/calendar", employee, null).get("events").size());

        String job = postId("/job-postings", hrUser, Map.of("title", "Developer", "status", "DRAFT"));
        call("POST", "/job-postings/" + job + "/publish", hrUser, null);
        String app = postId("/applications", hrUser, Map.of("jobId", job, "candidateName", "Candidate", "email", "candidate@example.test"));
        call("PATCH", "/applications/" + app, hrUser, Map.of("stage", "INTERVIEW"));
        call("POST", "/job-postings/" + job + "/close", hrUser, null);
        request("GET", "/applications", employee, null, 403);
        assertTrue(call("GET", "/approvals", hrUser, null).size() >= 5);
        request("GET", "/audit-events", employee, null, 403);
        assertTrue(call("GET", "/audit-events", admin, null).findValuesAsText("action").containsAll(
                List.of("PROFILE_UPDATED", "CALCULATED", "INTERVIEW", "APPROVED", "CANCELLED")));
        assertEquals("ADMIN", call("GET", "/dashboard", admin, null).get("role").asText());
        assertEquals("HR", call("GET", "/dashboard", hrUser, null).get("role").asText());

        // Verify password reset & deactivation protections
        String personToken = mintClerkJwt(newClerkUserId, "person@kanishk.test", newUserId);
        call("GET", "/me", personToken, null);
        request("POST", "/employees/" + newEmployee + "/reset-password", employee, Map.of("password", "HackedPass123"), 403);
        request("POST", "/employees/" + adminEmpId + "/reset-password", hrUser, Map.of("password", "HackedPass123"), 403);
        call("POST", "/employees/" + newEmployee + "/reset-password", hrUser, Map.of("password", "NewCompanyPass123"));
        assertEquals("NewCompanyPass123", fakeClerk.passwords.get(newClerkUserId));
        assertEquals("person@kanishk.test", call("PATCH", "/me/profile", personToken, Map.of("email", "spoof@kanishk.test")).get("email").asText());

        call("PATCH", "/employees/" + newEmployee, hrUser, Map.of("employmentStatus", "INACTIVE"));
        assertEquals(Boolean.FALSE, fakeClerk.active.get(newClerkUserId));
        request("GET", "/me", personToken, null, 401);

        // Test G: Invalid, missing, expired, and unprovisioned tokens return 401
        String expired = mintClerkJwt(firstRun.get(0).clerkUserId, "sysadmin@kanishk.test", adminUserId, -60);
        String unprovisioned = mintClerkJwt("user_not_in_hr_db", "stranger@example.com", UUID.randomUUID());
        request("GET", "/me", expired, null, 401);
        request("GET", "/me", unprovisioned, null, 401);
        request("GET", "/me", "not-a-valid-jwt", null, 401);
        request("GET", "/me", null, null, 401);
        request("POST", "/auth/login", null, Map.of("email", "sysadmin@kanishk.test", "password", "anything"), 401);

        // Health & static UI accessible without auth
        assertEquals(200, mvc.perform(MockMvcRequestBuilders.get("/api/health")).andReturn().getResponse().getStatus());
        assertEquals(302, mvc.perform(MockMvcRequestBuilders.get("/hrm/")).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(MockMvcRequestBuilders.get("/hrm/index.html")).andReturn().getResponse().getStatus());
    }
}

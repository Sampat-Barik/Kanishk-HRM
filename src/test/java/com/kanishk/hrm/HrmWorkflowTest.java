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

    private static final String TEST_ISSUER = "https://clerk.test";
    private static final String TEST_KID = "test-rs256-key";
    private KeyPair rsaKeys;
    String admin, employee;

    @TestConfiguration
    static class ClerkTestConfig {
        @Bean
        @Primary
        StubClerkDirectory stubClerkDirectory() {
            return new StubClerkDirectory();
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

    private String mintClerkJwt(String clerkUserId, String email, UUID externalId) throws Exception {
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
        claims.put("exp", Instant.now().getEpochSecond() + 3600);
        String payload = enc.encodeToString(json.writeValueAsBytes(claims));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(rsaKeys.getPrivate());
        sig.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        String signature = enc.encodeToString(sig.sign());
        return header + "." + payload + "." + signature;
    }

    private UUID seedAccount(String clerkUserId, String email, String role, String code, String fullName, UUID deptId) {
        UUID userId = UUID.randomUUID();
        UUID empId = UUID.randomUUID();
        db.update("insert into hr_user (id,email,password_hash,status,clerk_user_id) values (?,?,?,?,?)",
                userId, email, "CLERK_MANAGED", "ACTIVE", clerkUserId);
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
    void verifiesBothRolesAcrossAllModules() throws Exception {
        UUID hrDept = db.queryForObject("select id from hr_department limit 1", UUID.class);
        UUID adminUserId = seedAccount("user_clerk_hr_admin", "admin@kanishkservices.com", "HR_ADMIN", "HR-001", "HR Admin", hrDept);
        UUID empUserId = seedAccount("user_clerk_employee", "employee@kanishkservices.com", "EMPLOYEE", "EMP-001", "Demo Employee", hrDept);

        admin = mintClerkJwt("user_clerk_hr_admin", "admin@kanishkservices.com", adminUserId);
        employee = mintClerkJwt("user_clerk_employee", "employee@kanishkservices.com", empUserId);

        String me = call("GET", "/me", employee, null).get("employeeId").asText();
        String hr = call("GET", "/me", admin, null).get("employeeId").asText();
        request("GET", "/employees", employee, null, 403);
        request("GET", "/employees/" + hr, employee, null, 403);

        String department = postId("/departments", admin, Map.of("name", "Engineering"));
        var createdEmp = call("POST", "/employees", admin, Map.of(
                "fullName", "Test Person",
                "email", "person@kanishkservices.com",
                "password", "ExamplePass123",
                "hireDate", "2025-01-02",
                "employeeCode", "ENG1",
                "departmentId", department));
        String newEmployee = createdEmp.get("id").asText();
        String newClerkUserId = createdEmp.get("clerkUserId").asText();
        UUID newUserId = UUID.fromString(createdEmp.get("userId").asText());
        assertEquals("ExamplePass123", fakeClerk.passwords.get(newClerkUserId));
        assertEquals("CLERK_MANAGED", db.queryForObject("select password_hash from hr_user where id=?", String.class, newUserId));

        call("PATCH", "/employees/" + newEmployee, admin, Map.of("position", "Engineer"));
        assertEquals("Test Contact", call("PATCH", "/me/profile", employee, Map.of("emergencyContact", "Test Contact")).get("emergencyContact").asText());
        call("PATCH", "/me/location", employee, Map.of("location", "REMOTE"));
        String session = postId("/time-sessions/clock-in", employee, null);
        var status = call("GET", "/dashboard", employee, null).get("myStatus");
        assertTrue(status.get("online").booleanValue());
        assertEquals("REMOTE", status.get("location").asText());
        assertEquals("CLOCKED_IN", status.get("clockStatus").asText());
        assertEquals("CLOCKED_OUT", call("GET", "/employees/" + newEmployee, admin, null).get("clockStatus").asText());
        request("POST", "/time-sessions/clock-in", employee, null, 409);
        call("POST", "/time-sessions/" + session + "/clock-out", employee, null);

        OffsetDateTime from = OffsetDateTime.parse("2026-01-05T09:00:00+05:30");
        db.update("update hr_session set clock_in_at=?,clock_out_at=? where id=?", from, from.plusHours(9), UUID.fromString(session));
        String sheet = postId("/timesheets", employee, Map.of("startPeriod", "2026-01-05", "endPeriod", "2026-01-05"));
        call("POST", "/timesheets/" + sheet + "/approve", admin, null);
        String report = "/timesheets/report?from=2026-01-01&to=2026-01-31";
        assertEquals(9, call("GET", report, employee, null).get("approvedHours").asInt());
        request("GET", report + "&employeeId=" + hr, employee, null, 403);
        assertTrue(request("GET", "/timesheets/" + sheet + "/export", employee, null, 200).contains("REMOTE"));

        var pay = call("POST", "/payroll-records", admin, Map.of("timesheetId", sheet, "regularRate", 10000, "overtimeRate", 15000, "deductions", 5000, "currency", "INR"));
        assertEquals(95000, pay.get("grossPay").asLong());
        assertEquals(90000, pay.get("netPay").asLong());
        call("POST", "/payroll-records/" + pay.get("id").asText() + "/approve", admin, null);
        assertEquals(1, call("GET", "/payroll-records", employee, null).size());

        var leave = Map.of("startDate", "2026-01-09", "endDate", "2026-01-10", "type", "VACATION", "reason", "Travel");
        String id = postId("/leave-requests", employee, leave);
        request("POST", "/leave-requests", employee, leave, 409);
        call("POST", "/leave-requests/" + id + "/cancel", employee, null);
        id = postId("/leave-requests", employee, leave);
        request("POST", "/leave-requests/" + id + "/approve", employee, null, 403);
        call("POST", "/leave-requests/" + id + "/reject", admin, null);
        id = postId("/leave-requests", employee, leave);
        call("POST", "/leave-requests/" + id + "/approve", admin, null);

        String goal = postId("/goals", employee, Map.of("title", "Java", "description", "Finish training", "priority", "HIGH", "dueDate", "2026-12-31"));
        assertEquals("Finish training", call("GET", "/dashboard", employee, null).get("goals").get(0).get("description").asText());
        call("PATCH", "/goals/" + goal, employee, Map.of("progressPercent", 60));

        String module = postId("/training-modules", admin, Map.of("title", "Induction", "durationMinutes", 40, "mandatory", true));
        String enrollment = postId("/training-modules/" + module + "/enroll", employee, null);
        for (String step : List.of("start", "complete", "approve"))
            call("POST", "/training-enrollments/" + enrollment + "/" + step, step.equals("approve") ? admin : employee, null);
        assertEquals(100, call("GET", "/training-modules", employee, null).get(0).get("completionRate").asInt());

        String meeting = postId("/meetings", admin, Map.of("title", "Team meeting", "durationMinutes", 30, "startsAt", "2026-12-12T09:00:00Z", "joinUrl", "https://example.org/room", "attendeeIds", List.of(me)));
        call("GET", "/meetings/" + meeting + "/join", employee, null);
        call("POST", "/calendar/office-hours", admin, Map.of("weekday", 1, "closed", false, "opensAt", "09:00", "closesAt", "17:30"));
        call("POST", "/calendar/events", admin, Map.of("title", "Holiday", "kind", "HOLIDAY", "startsAt", "2026-12-25T00:00:00Z", "endsAt", "2026-12-26T00:00:00Z"));
        assertEquals(1, call("GET", "/calendar", employee, null).get("events").size());

        String job = postId("/job-postings", admin, Map.of("title", "Developer", "status", "DRAFT"));
        call("POST", "/job-postings/" + job + "/publish", admin, null);
        String app = postId("/applications", admin, Map.of("jobId", job, "candidateName", "Candidate", "email", "candidate@example.test"));
        call("PATCH", "/applications/" + app, admin, Map.of("stage", "INTERVIEW"));
        call("POST", "/job-postings/" + job + "/close", admin, null);
        request("GET", "/applications", employee, null, 403);
        assertTrue(call("GET", "/approvals", admin, null).size() >= 5);
        request("GET", "/audit-events", employee, null, 403);
        assertTrue(call("GET", "/audit-events", admin, null).findValuesAsText("action").containsAll(
                List.of("PROFILE_UPDATED", "CALCULATED", "INTERVIEW", "APPROVED", "CANCELLED")));
        call("GET", "/dashboard", admin, null);

        // Verify time-session and payroll filtering + employeeName
        assertEquals(1, call("GET", "/time-sessions?date=2026-01-05&employeeId=" + me, admin, null).size());
        assertEquals(0, call("GET", "/time-sessions?date=2026-01-06&employeeId=" + me, admin, null).size());
        request("GET", "/time-sessions?employeeId=" + hr, employee, null, 403);
        var filteredPay = call("GET", "/payroll-records?startPeriod=2026-01-01&endPeriod=2026-01-31", employee, null);
        assertEquals(1, filteredPay.size());
        assertEquals("Demo Employee", filteredPay.get(0).get("employeeName").asText());
        assertEquals(0, call("GET", "/payroll-records?startPeriod=2026-02-01&endPeriod=2026-02-28", employee, null).size());
        request("GET", "/payroll-records?employeeId=" + hr, employee, null, 403);

        // Verify duplicate/conflict protections (409)
        request("POST", "/payroll-records", admin, Map.of("timesheetId", sheet, "regularRate", 10000, "overtimeRate", 15000, "deductions", 0, "currency", "INR"), 409);
        request("POST", "/training-modules/" + module + "/enroll", employee, null, 409);
        request("POST", "/applications", admin, Map.of("jobId", job, "candidateName", "Another", "email", "another@example.test"), 409);
        String openJob = postId("/job-postings", admin, Map.of("title", "QA", "status", "PUBLISHED"));
        postId("/applications", admin, Map.of("jobId", openJob, "candidateName", "Dup", "email", "dup@example.test"));
        request("POST", "/applications", admin, Map.of("jobId", openJob, "candidateName", "Dup", "email", "dup@example.test"), 409);

        // Verify 400 validation and 404 missing records
        String missing = UUID.randomUUID().toString();
        request("POST", "/timesheets/" + missing + "/approve", admin, null, 404);
        request("POST", "/leave-requests/" + missing + "/approve", admin, null, 404);
        request("POST", "/payroll-records/" + missing + "/approve", admin, null, 404);
        request("POST", "/training-modules/" + missing + "/enroll", employee, null, 404);
        request("GET", "/meetings/" + missing + "/join", employee, null, 404);
        request("PATCH", "/applications/" + missing, admin, Map.of("stage", "IN_REVIEW"), 404);
        request("PATCH", "/goals/" + goal, employee, Map.of("progressPercent", 150), 400);
        request("PATCH", "/me/location", employee, Map.of("location", "MOON"), 400);
        request("POST", "/leave-requests", employee, Map.of("startDate", "2026-03-10", "endDate", "2026-03-05", "type", "VACATION"), 400);

        // Verify uninvited employee gets 403 on meeting join
        String hrOnlyMeeting = postId("/meetings", admin, Map.of("title", "HR Sync", "durationMinutes", 15, "startsAt", "2026-12-13T09:00:00Z", "joinUrl", "https://example.org/hr", "attendeeIds", List.of(hr)));
        request("GET", "/meetings/" + hrOnlyMeeting + "/join", employee, null, 403);

        // Verify HR password reset syncs to Clerk and blocks employees
        String personToken = mintClerkJwt(newClerkUserId, "person@kanishkservices.com", newUserId);
        call("GET", "/me", personToken, null);
        request("POST", "/employees/" + newEmployee + "/reset-password", employee, Map.of("password", "HackedPass123"), 403);
        call("POST", "/employees/" + newEmployee + "/reset-password", admin, Map.of("password", "NewCompanyPass123"));
        assertEquals("NewCompanyPass123", fakeClerk.passwords.get(newClerkUserId));

        // Verify employee cannot change their own company login email
        assertEquals("person@kanishkservices.com", call("PATCH", "/me/profile", personToken, Map.of("email", "spoof@kanishkservices.com")).get("email").asText());

        // Verify disabling employee bans in Clerk and immediately revokes API access
        call("PATCH", "/employees/" + newEmployee, admin, Map.of("employmentStatus", "INACTIVE"));
        assertEquals(Boolean.FALSE, fakeClerk.active.get(newClerkUserId));
        request("GET", "/me", personToken, null, 401);

        // Verify local login endpoint is disabled and unprovisioned/bad tokens return 401
        request("POST", "/auth/login", null, Map.of("email", "employee@kanishkservices.com", "password", "Employee@123"), 401);
        String unprovisioned = mintClerkJwt("user_not_in_hr_db", "stranger@example.com", UUID.randomUUID());
        request("GET", "/me", unprovisioned, null, 401);
        request("GET", "/me", "not-a-valid-jwt", null, 401);
        request("GET", "/me", null, null, 401);

        // Verify health endpoint and static /hrm/ redirect and page are accessible without auth
        assertEquals(200, mvc.perform(MockMvcRequestBuilders.get("/api/health")).andReturn().getResponse().getStatus());
        assertEquals(302, mvc.perform(MockMvcRequestBuilders.get("/hrm/")).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(MockMvcRequestBuilders.get("/hrm/index.html")).andReturn().getResponse().getStatus());
    }
}

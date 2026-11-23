package com.kanishk.hrm;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@Transactional
public class Accounts implements CommandLineRunner {
    private final Store s;
    private final ClerkDirectory clerk;

    Accounts(Store s, ClerkDirectory clerk) {
        this.s = s;
        this.clerk = clerk;
    }

    @Override
    public void run(String... args) {
        for (String role : List.of("ADMIN", "HR", "EMPLOYEE")) {
            if (s.rows("select id from hr_role where name=?", role).isEmpty()) {
                s.insert("role", "name", role);
            }
        }
        if (s.rows("select id from hr_department").isEmpty()) {
            s.insert("department", "name", "Human Resources");
        }
    }

    static final String ROLE_SUBQUERY = " (select case when r.name='HR_ADMIN' then 'ADMIN' else r.name end"
            + " from hr_user_role ur join hr_role r on r.id=ur.role_id where ur.user_id=e.user_id"
            + " order by case r.name when 'ADMIN' then 1 when 'HR_ADMIN' then 1 when 'HR' then 2 else 3 end limit 1)";

    static final String PROFILE = "select e.*, u.email, u.status as user_status, u.clerk_user_id,"
            + " coalesce(u.display_name, e.full_name) as display_name, u.created_at, u.updated_at, d.name as department,"
            + ROLE_SUBQUERY + " as role,"
            + " (u.status='ACTIVE' and u.clerk_user_id is not null) as online,"
            + " case when w.id is null then 'CLOCKED_OUT' else 'CLOCKED_IN' end as clock_status,"
            + " w.clock_in_at as current_clock_in_at"
            + " from hr_employee e join hr_user u on u.id=e.user_id join hr_department d on d.id=e.department_id"
            + " left join hr_session w on w.employee_id=e.id and w.clock_out_at is null";

    private String targetRole(UUID userId) {
        var list = s.rows("select case when r.name='HR_ADMIN' then 'ADMIN' else r.name end as role"
                + " from hr_user_role ur join hr_role r on r.id=ur.role_id where ur.user_id=?"
                + " order by case r.name when 'ADMIN' then 1 when 'HR_ADMIN' then 1 when 'HR' then 2 else 3 end limit 1", userId);
        return list.isEmpty() ? "EMPLOYEE" : String.valueOf(list.get(0).get("role"));
    }

    private long activeAdminCount() {
        Long count = s.db.queryForObject(
                "select count(distinct u.id) from hr_user u join hr_user_role ur on ur.user_id=u.id"
                        + " join hr_role r on r.id=ur.role_id where u.status='ACTIVE' and r.name in ('ADMIN','HR_ADMIN')",
                Long.class);
        return count == null ? 0L : count;
    }

    private static String normalizeAssignableRole(Fields f, String key) {
        String raw = f.choice(key, "EMPLOYEE", "HR", "ADMIN", "HR_ADMIN");
        return "HR_ADMIN".equals(raw) ? "ADMIN" : raw;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        var row = s.one(PROFILE + " where e.user_id=?", s.user());
        row.put("employeeId", row.get("id"));
        row.put("role", s.role());
        row.put("permissions", s.permissions());
        return row;
    }

    @PatchMapping("/me/profile")
    public Map<String, Object> profile(@RequestBody Fields f) {
        if (f.containsKey("role") || f.containsKey("employmentStatus") || f.containsKey("status")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role and account status cannot be modified via self-profile");
        }
        UUID id = s.employee();
        edit(id, f, false);
        s.audit("employee", id, "PROFILE_UPDATED");
        return me();
    }

    @GetMapping("/employees")
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public Object employees() {
        return s.rows(PROFILE + " order by e.full_name");
    }

    @GetMapping("/employees/{id}")
    public Object employee(@PathVariable UUID id) {
        if (!s.isHrOrAdmin()) s.owner(id);
        return s.one(PROFILE + " where e.id=?", id);
    }

    @PostMapping("/employees")
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public Object create(@RequestBody Fields f) {
        String password = f.text("password");
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("password");
        }
        String email = f.email();
        String fullName = f.text("fullName");
        String code = f.text("employeeCode");
        UUID department = f.id("departmentId");
        LocalDate hire = f.date("hireDate");
        String role = f.containsKey("role") ? normalizeAssignableRole(f, "role") : "EMPLOYEE";

        if (!s.isAdmin() && !"EMPLOYEE".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only ADMIN can provision ADMIN or HR accounts");
        }

        UUID userId = UUID.randomUUID();
        String clerkUserId = clerk.createUser(userId, email, password, fullName, role, code);
        s.db.update("insert into hr_user (id,email,password_hash,status,clerk_user_id,display_name,created_at,updated_at) values (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                userId, email, "CLERK_MANAGED", "ACTIVE", clerkUserId, fullName);
        s.db.update("insert into hr_user_role select ?,id from hr_role where name=?", userId, role);
        UUID empId = (UUID) s.insert("employee", "user_id,employee_code,full_name,department_id,employment_status,hire_date",
                userId, code, fullName, department, "ACTIVE", hire).get("id");
        edit(empId, f, true);
        s.audit("employee", empId, "CREATED");
        return employee(empId);
    }

    @PatchMapping("/employees/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public Object update(@PathVariable UUID id, @RequestBody Fields f) {
        edit(id, f, true);
        s.audit("employee", id, "PROFILE_UPDATED");
        return employee(id);
    }

    @DeleteMapping("/employees/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> deactivate(@PathVariable UUID id) {
        var row = s.one("select e.user_id, u.clerk_user_id, u.status from hr_employee e join hr_user u on u.id=e.user_id where e.id=? for update", id);
        UUID userId = (UUID) row.get("userId");
        String role = targetRole(userId);
        if ("ADMIN".equals(role)) {
            if (userId.equals(s.user()) || activeAdminCount() <= 1) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot deactivate the initial or last active ADMIN account");
            }
        }
        s.db.update("update hr_employee set employment_status='TERMINATED' where id=?", id);
        s.db.update("update hr_user set status='DISABLED', updated_at=CURRENT_TIMESTAMP where id=?", userId);
        String clerkUserId = (String) row.get("clerkUserId");
        if (clerkUserId != null) clerk.setActive(clerkUserId, false);
        s.audit("employee", id, "DEACTIVATED");
        return Map.of("status", "deactivated", "employeeId", id);
    }

    @PostMapping("/employees/{id}/reset-password")
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public Map<String, Object> resetPassword(@PathVariable UUID id, @RequestBody Fields f) {
        String password = f.text("password");
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("password");
        }
        var row = s.one("select u.id as user_id, u.clerk_user_id from hr_employee e join hr_user u on u.id=e.user_id where e.id=? for update", id);
        UUID targetUserId = (UUID) row.get("userId");
        String currentRole = targetRole(targetUserId);
        if (!s.isAdmin() && !"EMPLOYEE".equals(currentRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "HR cannot reset credentials for ADMIN or HR accounts");
        }
        String clerkUserId = (String) row.get("clerkUserId");
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Employee is not linked to a Clerk account");
        }
        clerk.resetPassword(clerkUserId, password);
        s.db.update("update hr_user set updated_at=CURRENT_TIMESTAMP where id=?", targetUserId);
        s.audit("employee", id, "PASSWORD_RESET");
        return Map.of("status", "ok", "employeeId", id);
    }

    private void edit(UUID id, Fields f, boolean privileged) {
        var row = s.one("select e.user_id, u.clerk_user_id, u.status from hr_employee e join hr_user u on u.id=e.user_id where e.id=? for update", id);
        UUID targetUserId = (UUID) row.get("userId");
        String clerkUserId = (String) row.get("clerkUserId");
        String currentRole = targetRole(targetUserId);

        if (privileged && !s.isAdmin()) {
            if (!"EMPLOYEE".equals(currentRole)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "HR cannot modify ADMIN or HR accounts");
            }
            if (f.containsKey("role")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only ADMIN can manage roles and permissions");
            }
        }

        if (f.containsKey("fullName")) {
            String fullName = f.text("fullName");
            s.db.update("update hr_employee set full_name=? where id=?", fullName, id);
            s.db.update("update hr_user set display_name=?, updated_at=CURRENT_TIMESTAMP where id=?", fullName, targetUserId);
        }
        var optionalCols = Map.of("phone", "phone", "address", "address", "emergencyContact", "emergency_contact");
        optionalCols.forEach((key, col) -> {
            if (f.containsKey(key)) s.db.update("update hr_employee set " + col + "=? where id=?", f.optional(key), id);
        });
        if (privileged && f.containsKey("email")) {
            String newEmail = f.email();
            s.db.update("update hr_user set email=?, updated_at=CURRENT_TIMESTAMP where id=?", newEmail, targetUserId);
            if (clerkUserId != null) clerk.updateEmail(clerkUserId, newEmail);
        }
        if (f.containsKey("avatarUrl")) {
            s.db.update("update hr_employee set avatar_url=? where id=?",
                    f.optional("avatarUrl") == null ? null : f.url("avatarUrl"), id);
        }
        if (privileged && f.containsKey("hireDate")) s.db.update("update hr_employee set hire_date=? where id=?", f.date("hireDate"), id);
        if (privileged && f.containsKey("employeeCode")) s.db.update("update hr_employee set employee_code=? where id=?", f.text("employeeCode"), id);
        if (privileged && f.containsKey("departmentId")) s.db.update("update hr_employee set department_id=? where id=?", f.id("departmentId"), id);
        if (privileged && f.containsKey("position")) s.db.update("update hr_employee set position=? where id=?", f.optional("position"), id);
        if (privileged && s.isAdmin() && f.containsKey("role")) {
            String role = normalizeAssignableRole(f, "role");
            if ("ADMIN".equals(currentRole) && !"ADMIN".equals(role) && activeAdminCount() <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot demote the last active ADMIN account");
            }
            s.db.update("delete from hr_user_role where user_id=?", targetUserId);
            s.db.update("insert into hr_user_role select ?,id from hr_role where name=?", targetUserId, role);
            s.db.update("update hr_user set updated_at=CURRENT_TIMESTAMP where id=?", targetUserId);
            if (clerkUserId != null) clerk.updateRole(clerkUserId, role);
        }
        if (privileged && f.containsKey("employmentStatus")) {
            String status = f.choice("employmentStatus", "ACTIVE", "INACTIVE", "TERMINATED");
            if ("ADMIN".equals(currentRole) && !"ACTIVE".equals(status) && activeAdminCount() <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot deactivate the last active ADMIN account");
            }
            s.db.update("update hr_employee set employment_status=? where id=?", status, id);
            s.db.update("update hr_user set status=?, updated_at=CURRENT_TIMESTAMP where id=?",
                    status.equals("ACTIVE") ? "ACTIVE" : "DISABLED", targetUserId);
            if (clerkUserId != null) clerk.setActive(clerkUserId, status.equals("ACTIVE"));
        }
    }

    @GetMapping("/admin/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> adminRoles() {
        var counts = s.rows("select case when r.name='HR_ADMIN' then 'ADMIN' else r.name end as role, count(distinct ur.user_id) as users"
                + " from hr_role r left join hr_user_role ur on ur.role_id=r.id"
                + " where r.name in ('ADMIN','HR','EMPLOYEE') group by r.name order by r.name");
        return Map.of(
                "roles", List.of("ADMIN", "HR", "EMPLOYEE"),
                "counts", counts);
    }

    @PatchMapping("/admin/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> updateUserRole(@PathVariable UUID userId, @RequestBody Fields f) {
        var user = s.one("select id, clerk_user_id from hr_user where id=? for update", userId);
        String newRole = normalizeAssignableRole(f, "role");
        String currentRole = targetRole(userId);
        if ("ADMIN".equals(currentRole) && !"ADMIN".equals(newRole) && activeAdminCount() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot demote the last active ADMIN account");
        }
        s.db.update("delete from hr_user_role where user_id=?", userId);
        s.db.update("insert into hr_user_role select ?,id from hr_role where name=?", userId, newRole);
        s.db.update("update hr_user set updated_at=CURRENT_TIMESTAMP where id=?", userId);
        String clerkUserId = (String) user.get("clerkUserId");
        if (clerkUserId != null) clerk.updateRole(clerkUserId, newRole);
        s.audit("user", userId, "ROLE_" + newRole);
        return Map.of("userId", userId, "role", newRole);
    }

    @GetMapping("/departments")
    public Object departments() {
        return s.rows("select * from hr_department order by name");
    }

    @PostMapping("/departments")
    @PreAuthorize("hasRole('ADMIN')")
    public Object dept(@RequestBody Fields f) {
        return s.insert("department", "name", f.text("name"));
    }

    interface ClerkDirectory {
        String createUser(UUID externalId, String email, String password, String fullName, String role, String employeeCode);
        void resetPassword(String clerkUserId, String newPassword);
        void updateEmail(String clerkUserId, String email);
        void updateRole(String clerkUserId, String role);
        void setActive(String clerkUserId, boolean active);
    }

    @Component
    static class HttpClerkDirectory implements ClerkDirectory {
        private final String secretKey;
        private final ObjectMapper json;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpClerkDirectory(@Value("${hrm.clerk-secret-key:}") String secretKey, ObjectMapper json) {
            this.secretKey = secretKey;
            this.json = json;
        }

        private JsonNode call(String path, String method, Map<String, Object> body) {
            if (secretKey == null || secretKey.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CLERK_SECRET_KEY is not configured");
            }
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("https://api.clerk.com/v1" + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + secretKey)
                        .header("Content-Type", "application/json");
                if ("GET".equals(method)) req.GET();
                else req.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "{}" : json.writeValueAsString(body)));
                HttpResponse<String> res = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
                JsonNode parsed = res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
                if (res.statusCode() >= 400) {
                    String msg = parsed.path("errors").path(0).path("long_message").asText(
                            parsed.path("errors").path(0).path("message").asText("Clerk API error"));
                    throw new ResponseStatusException(res.statusCode() == 422 ? HttpStatus.BAD_REQUEST : HttpStatus.valueOf(res.statusCode()), msg);
                }
                return parsed;
            } catch (ResponseStatusException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to reach Clerk API");
            }
        }

        private void revokeSessions(String clerkUserId) {
            if (secretKey == null || secretKey.isBlank()) return;
            try {
                JsonNode sessions = call("/sessions?user_id=" + URLEncoder.encode(clerkUserId, StandardCharsets.UTF_8) + "&status=active", "GET", null);
                for (JsonNode s : sessions) {
                    String sid = s.path("id").asText("");
                    if (!sid.isBlank()) call("/sessions/" + sid + "/revoke", "POST", Map.of());
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public String createUser(UUID externalId, String email, String password, String fullName, String role, String employeeCode) {
            String[] parts = fullName.trim().split("\\s+", 2);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("external_id", externalId.toString());
            body.put("email_address", List.of(email));
            body.put("first_name", parts[0]);
            if (parts.length > 1) body.put("last_name", parts[1]);
            body.put("password", password);
            body.put("skip_password_checks", true);
            body.put("delete_self_enabled", false);
            body.put("create_organization_enabled", false);
            body.put("public_metadata", Map.of("role", role, "employeeCode", employeeCode));
            return call("/users", "POST", body).path("id").asText();
        }

        @Override
        public void resetPassword(String clerkUserId, String newPassword) {
            call("/users/" + clerkUserId, "PATCH", Map.of("password", newPassword, "skip_password_checks", true));
            revokeSessions(clerkUserId);
        }

        @Override
        public void updateEmail(String clerkUserId, String email) {
            if (secretKey == null || secretKey.isBlank()) return;
            call("/email_addresses", "POST", Map.of("user_id", clerkUserId, "email_address", email, "verified", true, "primary", true));
        }

        @Override
        public void updateRole(String clerkUserId, String role) {
            if (secretKey == null || secretKey.isBlank()) return;
            call("/users/" + clerkUserId, "PATCH", Map.of("public_metadata", Map.of("role", role)));
            revokeSessions(clerkUserId);
        }

        @Override
        public void setActive(String clerkUserId, boolean active) {
            if (secretKey == null || secretKey.isBlank()) return;
            call("/users/" + clerkUserId + (active ? "/unban" : "/ban"), "POST", Map.of());
            if (!active) revokeSessions(clerkUserId);
        }
    }
}

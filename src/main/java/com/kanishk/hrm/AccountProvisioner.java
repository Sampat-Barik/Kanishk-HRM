package com.kanishk.hrm;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Protected, idempotent administrative CLI runner for provisioning initial ADMIN and HR accounts.
 * Never exposed as a public HTTP endpoint. Invoke explicitly via:
 *   java -jar hrm-backend.jar --hrm.provision=true --spring.main.web-application-type=none
 *
 * Reads operator-supplied account emails from environment variables (never hardcoded):
 *   PROVISION_ADMIN_EMAIL, PROVISION_ADMIN_NAME (optional),
 *   PROVISION_HR_EMAIL, PROVISION_HR_NAME (optional),
 *   CLERK_SECRET_KEY, DB_URL, DB_USERNAME, DB_PASSWORD.
 */
@Component
@ConditionalOnProperty(name = "hrm.provision", havingValue = "true")
class AccountProvisioner implements ApplicationRunner {
    private final ProvisioningService service;
    private final String adminEmail;
    private final String adminName;
    private final String hrEmail;
    private final String hrName;

    AccountProvisioner(
            ProvisioningService service,
            @Value("${PROVISION_ADMIN_EMAIL:}") String adminEmail,
            @Value("${PROVISION_ADMIN_NAME:System Administrator}") String adminName,
            @Value("${PROVISION_HR_EMAIL:}") String hrEmail,
            @Value("${PROVISION_HR_NAME:HR Manager}") String hrName) {
        this.service = service;
        this.adminEmail = adminEmail;
        this.adminName = adminName;
        this.hrEmail = hrEmail;
        this.hrName = hrName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank() || hrEmail.isBlank()) {
            throw new IllegalStateException("PROVISION_ADMIN_EMAIL and PROVISION_HR_EMAIL must be supplied via environment variables.");
        }
        var results = service.provisionInitialAccounts(adminEmail, adminName, hrEmail, hrName);
        for (var r : results) {
            System.out.printf("[PROVISION] role=%s email=%s clerkUserId=%s clerkAction=%s supabaseAction=%s%n",
                    r.role, r.email, r.clerkUserId, r.clerkAction, r.supabaseAction);
        }
    }
}

@Component
class ProvisioningService {
    private final Store s;
    private final ClerkProvisioningClient clerk;

    ProvisioningService(Store s, ClerkProvisioningClient clerk) {
        this.s = s;
        this.clerk = clerk;
    }

    static class ProvisionResult {
        final String role;
        final String email;
        final String clerkUserId;
        final String clerkAction;
        final String supabaseAction;

        ProvisionResult(String role, String email, String clerkUserId, String clerkAction, String supabaseAction) {
            this.role = role;
            this.email = email;
            this.clerkUserId = clerkUserId;
            this.clerkAction = clerkAction;
            this.supabaseAction = supabaseAction;
        }
    }

    @Transactional
    public List<ProvisionResult> provisionInitialAccounts(String adminEmail, String adminName, String hrEmail, String hrName) {
        // 1. Verify database reachability & canonical roles/department
        s.db.queryForObject("select 1", Integer.class);
        for (String role : List.of("ADMIN", "HR", "EMPLOYEE")) {
            if (s.rows("select id from hr_role where name=?", role).isEmpty()) {
                s.insert("role", "name", role);
            }
        }
        UUID deptId;
        var depts = s.rows("select id from hr_department order by name limit 1");
        if (depts.isEmpty()) {
            deptId = (UUID) s.insert("department", "name", "Human Resources").get("id");
        } else {
            deptId = (UUID) depts.get(0).get("id");
        }

        // 2. Verify Clerk credentials before making writes
        clerk.verifyConnection();

        List<ProvisionResult> results = new ArrayList<>();
        results.add(provisionSingle(adminEmail.trim().toLowerCase(Locale.ROOT), adminName.trim(), "ADMIN", "ADM-001", "System Administrator", deptId));
        results.add(provisionSingle(hrEmail.trim().toLowerCase(Locale.ROOT), hrName.trim(), "HR", "HR-001", "HR Manager", deptId));
        return results;
    }

    private ProvisionResult provisionSingle(String email, String displayName, String role, String defaultCode, String position, UUID deptId) {
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Invalid email for " + role);
        }

        // Idempotent Clerk lookup or passwordless creation (no hardcoded passwords)
        String existingClerkId = clerk.findUserIdByEmail(email);
        String clerkUserId;
        String clerkAction;
        if (existingClerkId != null) {
            clerkUserId = existingClerkId;
            clerkAction = "REUSED";
        } else {
            clerkUserId = clerk.createPasswordlessUser(email, displayName, role);
            clerkAction = "CREATED_PASSWORDLESS_SETUP_REQUIRED";
        }

        // Idempotent Supabase upsert (matched by clerk_user_id or lower(email))
        var existingRows = s.rows("select id, clerk_user_id from hr_user where clerk_user_id=? or lower(email)=lower(?) limit 1", clerkUserId, email);
        UUID userId;
        String supabaseAction;
        if (existingRows.isEmpty()) {
            userId = UUID.randomUUID();
            s.db.update("insert into hr_user (id,email,password_hash,status,clerk_user_id,display_name,created_at,updated_at) values (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    userId, email, "CLERK_MANAGED", "ACTIVE", clerkUserId, displayName);
            supabaseAction = "CREATED";
        } else {
            userId = (UUID) existingRows.get(0).get("id");
            s.db.update("update hr_user set email=?, clerk_user_id=?, display_name=?, status='ACTIVE', updated_at=CURRENT_TIMESTAMP where id=?",
                    email, clerkUserId, displayName, userId);
            supabaseAction = "SYNCED";
        }

        // Ensure authoritative role in Supabase without duplicates
        s.db.update("delete from hr_user_role where user_id=?", userId);
        s.db.update("insert into hr_user_role select ?,id from hr_role where name=?", userId, role);

        // Ensure one-to-one hr_employee row exists without duplicates
        if (s.rows("select id from hr_employee where user_id=?", userId).isEmpty()) {
            String code = s.rows("select id from hr_employee where employee_code=?", defaultCode).isEmpty()
                    ? defaultCode
                    : role + "-" + userId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
            s.insert("employee", "user_id,employee_code,full_name,department_id,employment_status,hire_date,position",
                    userId, code, displayName, deptId, "ACTIVE", LocalDate.now(), position);
        }

        clerk.syncExternalIdAndRole(clerkUserId, userId, role);
        return new ProvisionResult(role, email, clerkUserId, clerkAction, supabaseAction);
    }

    interface ClerkProvisioningClient {
        void verifyConnection();
        String findUserIdByEmail(String email);
        String createPasswordlessUser(String email, String displayName, String role);
        void syncExternalIdAndRole(String clerkUserId, UUID externalId, String role);
    }

    @Component
    static class HttpClerkProvisioningClient implements ClerkProvisioningClient {
        private final String secretKey;
        private final ObjectMapper json;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpClerkProvisioningClient(@Value("${hrm.clerk-secret-key:}") String secretKey, ObjectMapper json) {
            this.secretKey = secretKey;
            this.json = json;
        }

        private JsonNode request(String path, String method, Map<String, Object> body) {
            if (secretKey == null || secretKey.isBlank()) {
                throw new IllegalStateException("CLERK_SECRET_KEY must be configured.");
            }
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("https://api.clerk.com/v1" + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + secretKey)
                        .header("Content-Type", "application/json");
                if ("GET".equals(method)) req.GET();
                else req.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
                HttpResponse<String> res = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 400) {
                    throw new IllegalStateException("Clerk API rejected request with HTTP " + res.statusCode());
                }
                return res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to contact Clerk API", ex);
            }
        }

        @Override
        public void verifyConnection() {
            request("/users?limit=1", "GET", null);
        }

        @Override
        public String findUserIdByEmail(String email) {
            JsonNode arr = request("/users?email_address=" + URLEncoder.encode(email, StandardCharsets.UTF_8), "GET", null);
            if (arr.isArray() && arr.size() > 0) {
                return arr.get(0).path("id").asText(null);
            }
            return null;
        }

        @Override
        public String createPasswordlessUser(String email, String displayName, String role) {
            String[] parts = displayName.trim().split("\\s+", 2);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("email_address", List.of(email));
            body.put("first_name", parts[0]);
            if (parts.length > 1) body.put("last_name", parts[1]);
            body.put("skip_password_requirement", true);
            body.put("delete_self_enabled", false);
            body.put("create_organization_enabled", false);
            body.put("public_metadata", Map.of("role", role));
            return request("/users", "POST", body).path("id").asText();
        }

        @Override
        public void syncExternalIdAndRole(String clerkUserId, UUID externalId, String role) {
            request("/users/" + clerkUserId, "PATCH", Map.of(
                    "external_id", externalId.toString(),
                    "public_metadata", Map.of("role", role)));
        }
    }
}

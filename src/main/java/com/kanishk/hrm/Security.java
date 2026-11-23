package com.kanishk.hrm;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.*;
import javax.servlet.http.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
class Security {

    @Bean
    ClerkJwtVerifier clerkJwtVerifier(
            @Value("${hrm.clerk-issuer}") String clerkIssuer,
            @Value("${hrm.clerk-audience:}") String clerkAudience,
            ObjectMapper json) {
        return new ClerkJwtVerifier(clerkIssuer.trim().replaceAll("/+$", ""), clerkAudience.trim(), json);
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, Store store, ClerkJwtVerifier clerk,
                              @Value("${hrm.cors-origin}") String origin) throws Exception {
        CorsConfiguration cors = new CorsConfiguration();
        if (!origin.isBlank()) {
            List<String> origins = new ArrayList<>();
            for (String part : origin.split(",")) {
                String cleaned = part.trim().replaceAll("/+$", "");
                if (!cleaned.isEmpty()) origins.add(cleaned);
            }
            if (!origins.isEmpty()) cors.setAllowedOrigins(origins);
        }
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        http.cors().configurationSource(req -> cors).and().csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS);
        http.authorizeRequests()
                .antMatchers(HttpMethod.GET, "/", "/hrm", "/hrm/**", "/api/health").permitAll()
                .antMatchers(HttpMethod.HEAD, "/", "/hrm", "/hrm/**", "/api/health").permitAll()
                .antMatchers("/error").permitAll()
                .anyRequest().authenticated();
        http.exceptionHandling().authenticationEntryPoint((req, res, ex) -> res.sendError(401));
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                String header = req.getHeader("Authorization");
                if (header != null && header.startsWith("Bearer ")) {
                    Map<String, String> claims = clerk.verify(header.substring(7));
                    if (claims != null && !claims.getOrDefault("sub", "").isBlank()) {
                        String sub = claims.get("sub");
                        String email = claims.getOrDefault("email", "");
                        String extId = claims.getOrDefault("external_id", "");
                        var roles = store.rows(
                                "select u.id, u.clerk_user_id, r.name from hr_user u"
                                        + " join hr_user_role ur on ur.user_id=u.id"
                                        + " join hr_role r on ur.role_id=r.id"
                                        + " where u.status='ACTIVE' and ("
                                        + "   u.clerk_user_id=?"
                                        + "   or (u.clerk_user_id is null and ?<>'' and cast(u.id as varchar)=?)"
                                        + "   or (u.clerk_user_id is null and ?<>'' and lower(u.email)=lower(?))"
                                        + " )",
                                sub, extId, extId, email, email);
                        if (!roles.isEmpty()) {
                            Object matchedUserId = roles.get(0).get("id");
                            if (roles.get(0).get("clerkUserId") == null) {
                                store.db.update("update hr_user set clerk_user_id=?, updated_at=CURRENT_TIMESTAMP where id=? and clerk_user_id is null",
                                        sub, matchedUserId);
                            }
                            Set<String> normalized = new LinkedHashSet<>();
                            for (var r : roles) {
                                String roleName = String.valueOf(r.get("name"));
                                if ("HR_ADMIN".equals(roleName) || "ADMIN".equals(roleName)) normalized.add("ADMIN");
                                else if ("HR".equals(roleName)) normalized.add("HR");
                                else if ("EMPLOYEE".equals(roleName)) normalized.add("EMPLOYEE");
                            }
                            List<SimpleGrantedAuthority> grants = new ArrayList<>();
                            normalized.forEach(role -> grants.add(new SimpleGrantedAuthority("ROLE_" + role)));
                            SecurityContextHolder.getContext().setAuthentication(
                                    new UsernamePasswordAuthenticationToken(matchedUserId.toString(), null, grants));
                        }
                    }
                }
                chain.doFilter(req, res);
            }
        }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static class ClerkJwtVerifier {
        private final String issuer;
        private final String audience;
        private final ObjectMapper json;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();

        ClerkJwtVerifier(String issuer, String audience, ObjectMapper json) {
            this.issuer = issuer;
            this.audience = audience;
            this.json = json;
        }

        void registerKey(String kid, PublicKey key) {
            keys.put(kid, key);
        }

        Map<String, String> verify(String jwt) {
            try {
                String[] parts = jwt.split("\\.");
                if (parts.length != 3) return null;
                Base64.Decoder urlDecoder = Base64.getUrlDecoder();
                JsonNode header = json.readTree(urlDecoder.decode(parts[0]));
                JsonNode payload = json.readTree(urlDecoder.decode(parts[1]));
                if (!"RS256".equals(header.path("alg").asText())) return null;
                if (!issuer.equals(payload.path("iss").asText())) return null;
                if (!audience.isBlank() && !matchesAudience(payload.get("aud"), audience)) return null;
                long now = Instant.now().getEpochSecond();
                if (payload.path("exp").asLong(0) <= now) return null;
                if (payload.has("nbf") && payload.path("nbf").asLong(0) > now + 5) return null;

                String kid = header.path("kid").asText("");
                PublicKey key = keys.get(kid);
                if (key == null) {
                    refreshJwks();
                    key = keys.get(kid);
                }
                if (key == null) return null;

                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initVerify(key);
                sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                if (!sig.verify(urlDecoder.decode(parts[2]))) return null;

                Map<String, String> out = new HashMap<>();
                out.put("sub", payload.path("sub").asText(""));
                if (payload.hasNonNull("email")) out.put("email", payload.path("email").asText(""));
                if (payload.hasNonNull("external_id")) out.put("external_id", payload.path("external_id").asText(""));
                return out;
            } catch (Exception ignored) {
                return null;
            }
        }

        private static boolean matchesAudience(JsonNode audNode, String expected) {
            if (audNode == null || audNode.isNull()) return false;
            if (audNode.isTextual()) return expected.equals(audNode.asText());
            if (audNode.isArray()) {
                for (JsonNode item : audNode) {
                    if (expected.equals(item.asText())) return true;
                }
            }
            return false;
        }

        private synchronized void refreshJwks() throws Exception {
            if (issuer.isBlank()) return;
            HttpRequest req = HttpRequest.newBuilder(URI.create(issuer + "/.well-known/jwks.json"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;
            JsonNode root = json.readTree(res.body());
            KeyFactory kf = KeyFactory.getInstance("RSA");
            for (JsonNode k : root.path("keys")) {
                if ("RSA".equals(k.path("kty").asText()) && k.has("n") && k.has("e")) {
                    BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("n").asText()));
                    BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("e").asText()));
                    keys.put(k.path("kid").asText(""), kf.generatePublic(new RSAPublicKeySpec(n, e)));
                }
            }
        }
    }
}

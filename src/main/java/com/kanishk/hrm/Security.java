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
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableGlobalMethodSecurity(prePostEnabled=true)
class Security {
    static String digest(String token) {
        try {return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    @Bean BCryptPasswordEncoder passwords(){return new BCryptPasswordEncoder();}

    @Bean SecurityFilterChain chain(HttpSecurity http, Store store, Environment env, ObjectMapper json,
                                    @Value("${hrm.cors-origin}") String origin,
                                    @Value("${hrm.clerk-issuer:}") String clerkIssuer,
                                    @Value("${hrm.clerk-only:false}") boolean clerkOnly) throws Exception {
        CorsConfiguration cors=new CorsConfiguration();
        if(!origin.isBlank()) {
            List<String> origins=new ArrayList<>();
            for(String part:origin.split(",")){
                String cleaned=part.trim().replaceAll("/+$","");
                if(!cleaned.isEmpty()) origins.add(cleaned);
            }
            if(!origins.isEmpty()) cors.setAllowedOrigins(origins);
        } else if(Arrays.asList(env.getActiveProfiles()).contains("demo")) {
            cors.setAllowedOriginPatterns(List.of("http://localhost:*","http://127.0.0.1:*","null"));
        }
        cors.setAllowedMethods(List.of("GET","HEAD","POST","PATCH","OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization","Content-Type"));
        http.cors().configurationSource(req -> cors).and().csrf().disable().sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS);
        var authz=http.authorizeRequests().antMatchers(HttpMethod.GET,"/", "/hrm", "/hrm/**", "/api/health").permitAll()
                .antMatchers(HttpMethod.HEAD,"/", "/hrm", "/hrm/**", "/api/health").permitAll()
                .antMatchers("/error").permitAll();
        if(!clerkOnly) authz.antMatchers(HttpMethod.POST,"/api/auth/login").permitAll();
        authz.anyRequest().authenticated();
        http.exceptionHandling().authenticationEntryPoint((req,res,ex)->res.sendError(401));
        ClerkJwtVerifier clerk = clerkIssuer.isBlank() ? null : new ClerkJwtVerifier(clerkIssuer.trim().replaceAll("/+$",""), json);
        http.addFilterBefore(new OncePerRequestFilter(){
            protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException{
                String header=req.getHeader("Authorization");
                if(header!=null && header.startsWith("Bearer ")){
                    String token=header.substring(7);
                    List<Map<String,Object>> roles=List.of();
                    if(clerk!=null && token.chars().filter(c->c=='.').count()==2){
                        Map<String,String> claims=clerk.verify(token);
                        if(claims!=null){
                            roles=store.rows(
                                    "select u.id,r.name from hr_user u join hr_user_role ur on ur.user_id=u.id join hr_role r on ur.role_id=r.id"
                                    + " where u.status='ACTIVE' and (u.clerk_user_id=? or lower(u.email)=lower(?))",
                                    claims.get("sub"), claims.getOrDefault("email",""));
                        }
                    } else if(!clerkOnly) {
                        roles=store.rows("select u.id,r.name from hr_token t join hr_user u on u.id=t.user_id join hr_user_role ur on ur.user_id=u.id join hr_role r on ur.role_id=r.id where t.digest=? and t.expires_at>CURRENT_TIMESTAMP and u.status='ACTIVE'",digest(token));
                    }
                    if(!roles.isEmpty()){
                        List<SimpleGrantedAuthority> grants=new ArrayList<>();
                        roles.forEach(r->grants.add(new SimpleGrantedAuthority("ROLE_"+r.get("name"))));
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(roles.get(0).get("id").toString(),null,grants));
                    }
                }
                chain.doFilter(req,res);
            }
        },UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static class ClerkJwtVerifier {
        private final String issuer;
        private final ObjectMapper json;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();

        ClerkJwtVerifier(String issuer, ObjectMapper json) {
            this.issuer = issuer;
            this.json = json;
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
                return out;
            } catch (Exception ignored) {
                return null;
            }
        }

        private synchronized void refreshJwks() throws Exception {
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

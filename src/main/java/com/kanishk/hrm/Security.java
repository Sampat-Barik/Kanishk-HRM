package com.kanishk.hrm;

import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;
import javax.servlet.*;
import javax.servlet.http.*;
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
    @Bean SecurityFilterChain chain(HttpSecurity http,Store store,Environment env,@Value("${hrm.cors-origin}") String origin) throws Exception {
        CorsConfiguration cors=new CorsConfiguration();
        if(!origin.isBlank()) cors.setAllowedOrigins(List.of(origin));
        else if(Arrays.asList(env.getActiveProfiles()).contains("demo")) cors.setAllowedOriginPatterns(List.of("http://localhost:*","http://127.0.0.1:*","null"));
        cors.setAllowedMethods(List.of("GET","HEAD","POST","PATCH","OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization","Content-Type"));
        http.cors().configurationSource(req -> cors).and().csrf().disable().sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS);
        http.authorizeRequests().antMatchers(HttpMethod.GET,"/", "/hrm", "/hrm/**").permitAll()
                .antMatchers(HttpMethod.HEAD,"/", "/hrm", "/hrm/**").permitAll()
                .antMatchers(HttpMethod.POST,"/api/auth/login").permitAll().antMatchers("/error").permitAll().anyRequest().authenticated();
        http.exceptionHandling().authenticationEntryPoint((req,res,ex)->res.sendError(401));
        http.addFilterBefore(new OncePerRequestFilter(){
            protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException{
                String header=req.getHeader("Authorization");
                if(header!=null && header.startsWith("Bearer ")){
                    var roles=store.rows("select u.id,r.name from hr_token t join hr_user u on u.id=t.user_id join hr_user_role ur on ur.user_id=u.id join hr_role r on ur.role_id=r.id where t.digest=? and t.expires_at>CURRENT_TIMESTAMP and u.status='ACTIVE'",digest(header.substring(7)));
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
}

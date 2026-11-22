package com.kanishk.hrm;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@Transactional
public class Accounts implements CommandLineRunner {
    private final Store s;
    private final BCryptPasswordEncoder passwords;
    private final Environment env;
    Accounts(Store s,BCryptPasswordEncoder passwords,Environment env){this.s=s;this.passwords=passwords;this.env=env;}
    public void run(String... args){
        if(!s.rows("select id from hr_user").isEmpty())return;
        boolean demo=Arrays.asList(env.getActiveProfiles()).contains("demo");
        String email=env.getProperty("hrm.admin-email","");
        String password=env.getProperty("hrm.admin-password","");
        if(!demo && (email.isBlank() || password.length()<12)) throw new IllegalStateException("For a fresh database set HRM_ADMIN_EMAIL and HRM_ADMIN_PASSWORD (12+ characters), or activate the local demo profile");
        Object dept=s.insert("department","name","Human Resources").get("id");
        for(String role:List.of("EMPLOYEE","HR_ADMIN"))s.insert("role","name",role);
        provision(demo?"admin@hrm.local":email,demo?"Admin@123":password,"HR Admin","HR-001",dept,LocalDate.now(),"HR_ADMIN");
        if(demo)provision("employee@hrm.local","Employee@123","Demo Employee","EMP-001",dept,LocalDate.now(),"EMPLOYEE");
    }
    private UUID provision(String email,String password,String name,String code,Object department,LocalDate hire,String role){
        UUID user=(UUID)s.insert("user","email,password_hash,status",email.trim().toLowerCase(Locale.ROOT),passwords.encode(password),"ACTIVE").get("id");
        s.db.update("insert into hr_user_role select ?,id from hr_role where name=?",user,role);
        return (UUID)s.insert("employee","user_id,employee_code,full_name,department_id,employment_status,hire_date",user,code,name,department,"ACTIVE",hire).get("id");
    }
    @PostMapping("/auth/login")
    public Map<String,Object> login(@RequestBody Fields f){
        var users=s.rows("select id,password_hash from hr_user where email=? and status='ACTIVE'",f.email());
        if(users.isEmpty() || !passwords.matches(f.text("password"),(String)users.get(0).get("passwordHash"))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
        Object id=users.get(0).get("id");
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        s.db.update("delete from hr_token where expires_at<=CURRENT_TIMESTAMP");
        s.db.update("insert into hr_token values (?,?,?)",Security.digest(token),id,OffsetDateTime.now().plusHours(8));
        var roles=s.rows("select name from hr_role r join hr_user_role ur on ur.role_id=r.id where user_id=? order by name desc",id);
        return Map.of("accessToken",token,"tokenType","Bearer","role",roles.get(0).get("name"),"roles",roles);
    }
    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String header){
        if(header!=null && header.startsWith("Bearer "))s.db.update("delete from hr_token where digest=?",Security.digest(header.substring(7)));
    }
    static final String PROFILE = "select e.*, u.email, u.status as user_status, d.name as department,"
            + " exists (select 1 from hr_token t where t.user_id=e.user_id and t.expires_at>CURRENT_TIMESTAMP) as online,"
            + " case when w.id is null then 'CLOCKED_OUT' else 'CLOCKED_IN' end as clock_status,"
            + " w.clock_in_at as current_clock_in_at"
            + " from hr_employee e join hr_user u on u.id=e.user_id join hr_department d on d.id=e.department_id"
            + " left join hr_session w on w.employee_id=e.id and w.clock_out_at is null";
    @GetMapping("/me") public Map<String,Object> me(){var row=s.one(PROFILE+" where e.user_id=?",s.user());row.put("employeeId",row.get("id"));row.put("role",s.admin()?"HR_ADMIN":"EMPLOYEE");return row;}
    @PatchMapping("/me/profile") public Map<String,Object> profile(@RequestBody Fields f){
        UUID id=s.employee();
        edit(id,f,false);
        s.audit("employee",id,"PROFILE_UPDATED");
        return me();
    }
    @GetMapping("/employees") @PreAuthorize("hasRole('HR_ADMIN')") public Object employees(){return s.rows(PROFILE+" order by e.full_name");}
    @GetMapping("/employees/{id}") public Object employee(@PathVariable UUID id){if(!s.admin())s.owner(id);return s.one(PROFILE+" where e.id=?",id);}
    @PostMapping("/employees") @PreAuthorize("hasRole('HR_ADMIN')")
    public Object create(@RequestBody Fields f){
        String password=f.text("password");if(password.length()<8 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("password");
        UUID id=provision(f.email(),password,f.text("fullName"),f.text("employeeCode"),f.id("departmentId"),f.date("hireDate"),"EMPLOYEE");
        edit(id,f,true);
        s.audit("employee",id,"CREATED");
        return employee(id);
    }
    @PatchMapping("/employees/{id}") @PreAuthorize("hasRole('HR_ADMIN')")
    public Object update(@PathVariable UUID id,@RequestBody Fields f){
        edit(id,f,true);
        s.audit("employee",id,"PROFILE_UPDATED");
        return employee(id);
    }
    private void edit(UUID id,Fields f,boolean hr){
        var row=s.one("select user_id from hr_employee where id=? for update",id);
        var cols=Map.of("phone","phone","address","address","emergencyContact","emergency_contact","fullName","full_name");
        cols.forEach((key,col)->{if(f.containsKey(key))s.db.update("update hr_employee set "+col+"=? where id=?",f.text(key),id);});
        if(f.containsKey("email"))s.db.update("update hr_user set email=? where id=?",f.email(),row.get("userId"));
        if(f.containsKey("avatarUrl"))s.db.update("update hr_employee set avatar_url=? where id=?",
                f.get("avatarUrl")==null ? null : f.url("avatarUrl"),id);
        if(hr && f.containsKey("hireDate"))s.db.update("update hr_employee set hire_date=? where id=?",f.date("hireDate"),id);
        if(hr && f.containsKey("employeeCode"))s.db.update("update hr_employee set employee_code=? where id=?",f.text("employeeCode"),id);
        if(hr && f.containsKey("departmentId"))s.db.update("update hr_employee set department_id=? where id=?",f.id("departmentId"),id);
        if(hr && f.containsKey("position"))s.db.update("update hr_employee set position=? where id=?",f.text("position"),id);
        if(hr && f.containsKey("employmentStatus")){
            String status=f.choice("employmentStatus","ACTIVE","INACTIVE","TERMINATED");
            s.db.update("update hr_employee set employment_status=? where id=?",status,id);
            s.db.update("update hr_user set status=? where id=?",status.equals("ACTIVE")?"ACTIVE":"DISABLED",row.get("userId"));
            if(!status.equals("ACTIVE"))s.db.update("delete from hr_token where user_id=?",row.get("userId"));
        }
    }
    @GetMapping("/departments") public Object departments(){return s.rows("select * from hr_department order by name");}
    @PostMapping("/departments") @PreAuthorize("hasRole('HR_ADMIN')") public Object dept(@RequestBody Fields f){return s.insert("department","name",f.text("name"));}
}

package com.kanishk.hrm;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@Transactional
public class Development {
    private final Store s;
    Development(Store s){this.s=s;}
    @GetMapping("/goals") public Object goals(){return s.scoped("goal");}
    @PostMapping("/goals") public Object goal(@RequestBody Fields f){
        UUID owner=f.containsKey("employeeId")?f.id("employeeId"):s.employee();
        if(!s.admin())s.owner(owner);
        return s.insert("goal","employee_id,title,description,priority,progress_percent,due_date",
                owner,f.text("title"),f.optional("description"),f.choice("priority","LOW","MEDIUM","HIGH"),0,f.date("dueDate"));
    }
    @PatchMapping("/goals/{id}") public Object progress(@PathVariable UUID id,@RequestBody Fields f){
        var goal=s.one("select * from hr_goal where id=?",id);if(!s.admin())s.owner(goal.get("employeeId"));
        s.db.update("update hr_goal set progress_percent=? where id=?",f.number("progressPercent",0,100),id);
        return s.one("select * from hr_goal where id=?",id);
    }
    @GetMapping("/training-modules")
    public Object modules() {
        return s.rows("select m.*, coalesce((select round(100.0 * sum(case when e.status in"
                + " ('COMPLETED','APPROVED') then 1 else 0 end) / nullif(count(*),0), 1)"
                + " from hr_enrollment e where e.module_id=m.id), 0) as completion_rate"
                + " from hr_module m order by m.title");
    }
    @PostMapping("/training-modules") @PreAuthorize("hasRole('HR_ADMIN')") public Object create(@RequestBody Fields f){
        return s.insert("module","title,description,duration_minutes,mandatory",f.text("title"),f.optional("description"),f.number("durationMinutes",1,100000),f.bool("mandatory"));
    }
    @PatchMapping("/training-modules/{id}") @PreAuthorize("hasRole('HR_ADMIN')") public Object edit(@PathVariable UUID id,@RequestBody Fields f){
        var row=s.one("select * from hr_module where id=? for update",id);
        s.db.update("update hr_module set title=?,description=?,duration_minutes=?,mandatory=? where id=?",
            f.containsKey("title")?f.text("title"):row.get("title"),f.containsKey("description")?f.optional("description"):row.get("description"),
            f.containsKey("durationMinutes")?f.number("durationMinutes",1,100000):row.get("durationMinutes"),f.containsKey("mandatory")?f.bool("mandatory"):row.get("mandatory"),id);
        return s.one("select * from hr_module where id=?",id);
    }
    @GetMapping("/training-enrollments") public Object enrollments(){return s.scoped("enrollment");}
    @PostMapping("/training-modules/{id}/enroll") public Object enroll(@PathVariable UUID id,@RequestBody(required=false) Fields f){
        s.one("select id from hr_module where id=?",id);
        UUID owner=(f!=null && f.containsKey("employeeId"))?f.id("employeeId"):s.employee();
        if(!s.admin())s.owner(owner);
        s.one("select id from hr_employee where id=?",owner);
        return s.insert("enrollment","employee_id,module_id,status,completion_percent",owner,id,"ASSIGNED",0);
    }
    @PostMapping("/training-enrollments/{id}/start") public Object start(@PathVariable UUID id){
        s.owner(s.one("select employee_id from hr_enrollment where id=?",id).get("employeeId"));
        return s.transition("enrollment",id,"ASSIGNED","IN_PROGRESS",false);
    }
    @PatchMapping("/training-enrollments/{id}") public Object progressTraining(@PathVariable UUID id,@RequestBody Fields f){
        var row=s.one("select * from hr_enrollment where id=? for update",id);s.owner(row.get("employeeId"));
        if(!"IN_PROGRESS".equals(row.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT);
        s.db.update("update hr_enrollment set completion_percent=? where id=?",f.number("completionPercent",0,99),id);
        return s.one("select * from hr_enrollment where id=?",id);
    }
    @PostMapping("/training-enrollments/{id}/complete") public Object complete(@PathVariable UUID id){
        s.owner(s.one("select employee_id from hr_enrollment where id=?",id).get("employeeId"));
        s.transition("enrollment",id,"IN_PROGRESS","COMPLETED",false);
        s.db.update("update hr_enrollment set completion_percent=100,completed_at=CURRENT_TIMESTAMP where id=?",id);
        return s.one("select * from hr_enrollment where id=?",id);
    }
    @PostMapping("/training-enrollments/{id}/approve") @PreAuthorize("hasRole('HR_ADMIN')") public Object approve(@PathVariable UUID id){return s.transition("enrollment",id,"COMPLETED","APPROVED",true);}
}

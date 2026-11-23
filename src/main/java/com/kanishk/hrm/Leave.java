package com.kanishk.hrm;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/leave-requests")
@Transactional
public class Leave {
    private final Store s;
    Leave(Store s){this.s=s;}
    @GetMapping public Object mine(){return s.rows("select * from hr_leave where employee_id=?",s.employee());}
    @GetMapping("/all") @PreAuthorize("hasAnyRole('ADMIN','HR')") public Object all(){return s.rows("select l.*,e.full_name as employee from hr_leave l join hr_employee e on e.id=l.employee_id order by submitted_at desc");}
    @PostMapping public Object submit(@RequestBody Fields f){
        s.lockEmployee(); LocalDate from=f.date("startDate"),to=f.date("endDate");UUID employee=s.employee();
        if(to.isBefore(from))throw new IllegalArgumentException("date range");
        if(!s.rows("select id from hr_leave where employee_id=? and status in ('PENDING','APPROVED') and start_date<=? and end_date>=?",employee,to,from).isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Leave dates overlap");
        var leave=s.insert("leave","employee_id,type,start_date,end_date,reason,status,submitted_at",employee,f.text("type"),from,to,f.optional("reason"),"PENDING",OffsetDateTime.now());
        s.audit("leave",(UUID)leave.get("id"),"SUBMITTED");
        return leave;
    }
    @PostMapping("/{id}/cancel") public Object cancel(@PathVariable UUID id){
        s.owner(s.one("select employee_id from hr_leave where id=?",id).get("employeeId"));
        return s.transition("leave",id,"PENDING","CANCELLED",false);
    }
    @PostMapping("/{id}/{decision:approve|reject}") @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public Object review(@PathVariable UUID id,@PathVariable String decision){return s.transition("leave",id,"PENDING",decision.equals("approve")?"APPROVED":"REJECTED",true);}
}

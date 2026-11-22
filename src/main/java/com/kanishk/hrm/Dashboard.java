package com.kanishk.hrm;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
@Transactional(readOnly = true)
public class Dashboard {
    private final Store s;
    private final ZoneId zone;
    Dashboard(Store s,@Value("${hrm.zone}") String zone){this.s=s;this.zone=ZoneId.of(zone);}
    private long count(String table,String predicate,Object... args){return s.db.queryForObject("select count(*) from hr_"+table+" where "+predicate,Long.class,args);}
    @GetMapping public Object dashboard(){
        UUID employee=s.employee();var now=OffsetDateTime.now();var today=LocalDate.now(zone);
        var begin=today.atStartOfDay(zone).toOffsetDateTime();
        var end=today.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        Map<String,Object> result=new LinkedHashMap<>();result.put("date",today);
        var profile=s.one(Accounts.PROFILE+" where e.id=?",employee);
        result.put("myStatus",Map.of("online",profile.get("online"),"location",profile.get("location"),
                "clockStatus",profile.get("clockStatus")));
        var sessions=s.rows("select * from hr_session where employee_id=? and clock_in_at<? and (clock_out_at is null or clock_out_at>?) order by clock_in_at",employee,end,begin);
        long seconds=0;
        for(var row:sessions){
            var start=(OffsetDateTime)row.get("clockInAt");var stop=(OffsetDateTime)row.get("clockOutAt");
            if(stop==null)stop=now;
            seconds+=Math.max(0,Duration.between(start.isBefore(begin)?begin:start,stop.isAfter(end)?end:stop).getSeconds());
        }
        result.put("todaySeconds",seconds);result.put("sessions",sessions);
        result.put("goals",s.rows("select * from hr_goal where employee_id=? order by due_date",employee));
        result.put("upcomingMeetings",s.rows("select m.* from hr_meeting m join hr_attendee a on m.id=a.meeting_id where a.employee_id=? and starts_at>=? order by starts_at",employee,now));
        if(s.admin()){
            result.put("pending",Map.of("leave",count("leave","status='PENDING'"),"timesheets",count("sheet","status='PENDING'"),"payroll",count("payroll","status='PENDING'"),"training",count("enrollment","status='COMPLETED'")));
            result.put("employees",count("employee","employment_status='ACTIVE'"));
            result.put("online",count("employee","exists (select 1 from hr_token where hr_token.user_id=hr_employee.user_id and expires_at>CURRENT_TIMESTAMP)"));
            result.put("byLocation",s.rows("select location,count(*) as total from hr_employee where employment_status='ACTIVE' group by location"));
        }
        return result;
    }
}

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
public class Office {
    private final Store s;
    Office(Store s){this.s=s;}
    @PostMapping("/meetings") @PreAuthorize("hasRole('HR_ADMIN')") public Object schedule(@RequestBody Fields f){
        var meeting=s.insert("meeting","title,starts_at,duration_minutes,join_url",f.text("title"),f.instant("startsAt"),f.number("durationMinutes",1,1440),f.url("joinUrl"));
        if(!(f.get("attendeeIds") instanceof List))throw new IllegalArgumentException("attendeeIds");
        Set<UUID> attendees=new LinkedHashSet<>();attendees.add(s.employee());
        for(Object id:(List<?>)f.get("attendeeIds"))attendees.add(UUID.fromString(String.valueOf(id)));
        for(UUID id:attendees)s.db.update("insert into hr_attendee values (?,?)",meeting.get("id"),id);
        meeting.put("attendeeIds",attendees);return meeting;
    }
    @GetMapping("/meetings") public Object meetings(){
        var meetings=s.admin()?s.rows("select * from hr_meeting order by starts_at"):s.rows("select m.* from hr_meeting m join hr_attendee a on m.id=a.meeting_id where a.employee_id=? order by m.starts_at",s.employee());
        for(var m:meetings)m.put("attendees",s.rows("select e.id,e.full_name from hr_employee e join hr_attendee a on e.id=a.employee_id where a.meeting_id=?",m.get("id")));
        return meetings;
    }
    @GetMapping("/meetings/{id}/join") public Object join(@PathVariable UUID id){
        var meeting=s.one("select join_url from hr_meeting where id=?",id);
        if(!s.admin() && s.rows("select employee_id from hr_attendee where employee_id=? and meeting_id=?",s.employee(),id).isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Not invited to this meeting");
        return meeting;
    }
    @GetMapping("/calendar") public Object calendar(){return Map.of("officeHours",s.rows("select * from hr_office_hours order by weekday"),"events",s.rows("select * from hr_calendar order by starts_at"),"meetings",meetings());}
    @PostMapping("/calendar/office-hours") @PreAuthorize("hasRole('HR_ADMIN')") public Object hours(@RequestBody Fields f){
        long day=f.number("weekday",1,7);boolean closed=f.bool("closed");LocalTime open=closed?null:LocalTime.parse(f.text("opensAt")),end=closed?null:LocalTime.parse(f.text("closesAt"));
        if(!closed && !end.isAfter(open))throw new IllegalArgumentException("hours");
        s.db.update("delete from hr_office_hours where weekday=?",day);
        s.db.update("insert into hr_office_hours values (?,?,?,?)",day,closed,open,end);
        return s.one("select * from hr_office_hours where weekday=?",day);
    }
    @PostMapping("/calendar/events") @PreAuthorize("hasRole('HR_ADMIN')") public Object event(@RequestBody Fields f){
        var start=f.instant("startsAt");var end=f.instant("endsAt");if(end.isBefore(start))throw new IllegalArgumentException("endsAt");
        return s.insert("calendar","title,kind,starts_at,ends_at,location",f.text("title"),f.choice("kind","HOLIDAY","EVENT"),start,end,f.optional("location"));
    }
}

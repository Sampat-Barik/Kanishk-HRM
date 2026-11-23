package com.kanishk.hrm;

import java.time.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','HR')")
public class Recruitment {
    private final Store s;
    Recruitment(Store s){this.s=s;}
    @GetMapping("/job-postings") public Object jobs(){return s.rows("select j.*,(select count(*) from hr_application a where a.job_id=j.id) as application_count from hr_job j order by title");}
    @PostMapping("/job-postings") public Object post(@RequestBody Fields f){
        String status=f.containsKey("status")?f.choice("status","DRAFT","PUBLISHED"):"PUBLISHED";
        return s.insert("job","title,description,status,published_at",f.text("title"),f.optional("description"),status,status.equals("PUBLISHED")?OffsetDateTime.now():null);
    }
    @PostMapping("/job-postings/{id}/{action:publish|close}") public Object change(@PathVariable UUID id,@PathVariable String action){
        boolean publish=action.equals("publish");s.transition("job",id,publish?"DRAFT":"PUBLISHED",publish?"PUBLISHED":"CLOSED",false);
        if(publish)s.db.update("update hr_job set published_at=CURRENT_TIMESTAMP where id=?",id);
        return s.one("select * from hr_job where id=?",id);
    }
    @GetMapping("/applications") public Object applications(){return s.rows("select * from hr_application order by candidate_name");}
    @PostMapping("/applications") public Object apply(@RequestBody Fields f){
        UUID job=f.id("jobId");if(!"PUBLISHED".equals(s.one("select status from hr_job where id=? for update",job).get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Job is closed");
        return s.insert("application","job_id,candidate_name,email,stage",job,f.text("candidateName"),f.email(),"APPLIED");
    }
    @PatchMapping("/applications/{id}") public Object review(@PathVariable UUID id,@RequestBody Fields f){
        s.one("select id from hr_application where id=? for update",id);
        String stage=f.choice("stage","IN_REVIEW","INTERVIEW","OFFERED","HIRED","REJECTED");
        if(s.db.update("update hr_application set stage=? where id=? and stage not in ('HIRED','REJECTED')",stage,id)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Application unavailable for review");
        s.audit("application",id,stage);
        return s.one("select * from hr_application where id=?",id);
    }
}

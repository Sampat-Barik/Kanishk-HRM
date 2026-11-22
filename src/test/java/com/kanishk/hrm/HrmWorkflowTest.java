package com.kanishk.hrm;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:workflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class HrmWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    String admin, employee;

    String request(String method,String path,String token,Object data,int expected) throws Exception {
        var req=MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),"/api"+path);
        if(token!=null)req.header("Authorization","Bearer "+token);
        if(data!=null)req.contentType("application/json").content(json.writeValueAsBytes(data));
        var response=mvc.perform(req).andReturn().getResponse();
        assertEquals(expected,response.getStatus(),method+" "+path+" "+response.getErrorMessage());
        return response.getContentAsString();
    }
    JsonNode call(String method,String path,String token,Object data) throws Exception {
        return json.readTree(request(method,path,token,data,200));
    }
    String postId(String path,String token,Object data) throws Exception {return call("POST",path,token,data).get("id").asText();}
    String login(String name,String password) throws Exception {
        return call("POST","/auth/login",null,Map.of("email",name+"@hrm.local","password",password)).get("accessToken").asText();
    }
    @Test void verifiesBothRolesAcrossAllModules() throws Exception {
        admin=login("admin","Admin@123");employee=login("employee","Employee@123");
        String me=call("GET","/me",employee,null).get("employeeId").asText();
        String hr=call("GET","/me",admin,null).get("employeeId").asText();
        request("GET","/employees",employee,null,403);
        request("GET","/employees/"+hr,employee,null,403);
        String department=postId("/departments",admin,Map.of("name","Engineering"));
        String newEmployee=postId("/employees",admin,Map.of("fullName","Test Person","email","person@example.test","password","ExamplePass123","hireDate","2025-01-02","employeeCode","ENG1","departmentId",department));
        call("PATCH","/employees/"+newEmployee,admin,Map.of("position","Engineer"));
        assertEquals("Test Contact",call("PATCH","/me/profile",employee,Map.of("emergencyContact","Test Contact")).get("emergencyContact").asText());
        call("PATCH","/me/location",employee,Map.of("location","REMOTE"));
        String session=postId("/time-sessions/clock-in",employee,null);
        var status=call("GET","/dashboard",employee,null).get("myStatus");
        assertTrue(status.get("online").booleanValue());
        assertEquals("REMOTE",status.get("location").asText());
        assertEquals("CLOCKED_IN",status.get("clockStatus").asText());
        assertFalse(call("GET","/employees/"+newEmployee,admin,null).get("online").booleanValue());
        request("POST","/time-sessions/clock-in",employee,null,409);
        call("POST","/time-sessions/"+session+"/clock-out",employee,null);
        OffsetDateTime from=OffsetDateTime.parse("2026-01-05T09:00:00+05:30");
        db.update("update hr_session set clock_in_at=?,clock_out_at=? where id=?",from,from.plusHours(9),UUID.fromString(session));
        String sheet=postId("/timesheets",employee,Map.of("startPeriod","2026-01-05","endPeriod","2026-01-05"));
        call("POST","/timesheets/"+sheet+"/approve",admin,null);
        String report="/timesheets/report?from=2026-01-01&to=2026-01-31";
        assertEquals(9,call("GET",report,employee,null).get("approvedHours").asInt());
        request("GET",report+"&employeeId="+hr,employee,null,403);
        assertTrue(request("GET","/timesheets/"+sheet+"/export",employee,null,200).contains("REMOTE"));
        var pay=call("POST","/payroll-records",admin,Map.of("timesheetId",sheet,"regularRate",10000,"overtimeRate",15000,"deductions",5000,"currency","INR"));
        assertEquals(95000,pay.get("grossPay").asLong()); assertEquals(90000,pay.get("netPay").asLong());
        call("POST","/payroll-records/"+pay.get("id").asText()+"/approve",admin,null);
        assertEquals(1,call("GET","/payroll-records",employee,null).size());
        var leave=Map.of("startDate","2026-01-09","endDate","2026-01-10","type","VACATION","reason","Travel");
        String id=postId("/leave-requests",employee,leave);
        request("POST","/leave-requests",employee,leave,409);
        call("POST","/leave-requests/"+id+"/cancel",employee,null);
        id=postId("/leave-requests",employee,leave);
        request("POST","/leave-requests/"+id+"/approve",employee,null,403);
        call("POST","/leave-requests/"+id+"/reject",admin,null);
        id=postId("/leave-requests",employee,leave);call("POST","/leave-requests/"+id+"/approve",admin,null);
        String goal=postId("/goals",employee,Map.of("title","Java","description","Finish training","priority","HIGH","dueDate","2026-12-31"));
        assertEquals("Finish training",call("GET","/dashboard",employee,null).get("goals").get(0).get("description").asText());
        call("PATCH","/goals/"+goal,employee,Map.of("progressPercent",60));
        String module=postId("/training-modules",admin,Map.of("title","Induction","durationMinutes",40,"mandatory",true));
        String enrollment=postId("/training-modules/"+module+"/enroll",employee,null);
        for(String step:List.of("start","complete","approve"))
            call("POST","/training-enrollments/"+enrollment+"/"+step,step.equals("approve")?admin:employee,null);
        assertEquals(100,call("GET","/training-modules",employee,null).get(0).get("completionRate").asInt());
        String meeting=postId("/meetings",admin,Map.of("title","Team meeting","durationMinutes",30,"startsAt","2026-12-12T09:00:00Z","joinUrl","https://example.org/room","attendeeIds",List.of(me)));
        call("GET","/meetings/"+meeting+"/join",employee,null);
        call("POST","/calendar/office-hours",admin,Map.of("weekday",1,"closed",false,"opensAt","09:00","closesAt","17:30"));
        call("POST","/calendar/events",admin,Map.of("title","Holiday","kind","HOLIDAY","startsAt","2026-12-25T00:00:00Z","endsAt","2026-12-26T00:00:00Z"));
        assertEquals(1,call("GET","/calendar",employee,null).get("events").size());
        String job=postId("/job-postings",admin,Map.of("title","Developer","status","DRAFT"));
        call("POST","/job-postings/"+job+"/publish",admin,null);
        String app=postId("/applications",admin,Map.of("jobId",job,"candidateName","Candidate","email","candidate@example.test"));
        call("PATCH","/applications/"+app,admin,Map.of("stage","INTERVIEW")); call("POST","/job-postings/"+job+"/close",admin,null);
        request("GET","/applications",employee,null,403);
        assertTrue(call("GET","/approvals",admin,null).size()>=5);
        request("GET","/audit-events",employee,null,403);
        assertTrue(call("GET","/audit-events",admin,null).findValuesAsText("action").containsAll(
                List.of("PROFILE_UPDATED","CALCULATED","INTERVIEW","APPROVED","CANCELLED")));
        call("GET","/dashboard",admin,null);
        // Verify time-session and payroll filtering + employeeName
        assertEquals(1,call("GET","/time-sessions?date=2026-01-05&employeeId="+me,admin,null).size());
        assertEquals(0,call("GET","/time-sessions?date=2026-01-06&employeeId="+me,admin,null).size());
        request("GET","/time-sessions?employeeId="+hr,employee,null,403);
        var filteredPay=call("GET","/payroll-records?startPeriod=2026-01-01&endPeriod=2026-01-31",employee,null);
        assertEquals(1,filteredPay.size());
        assertEquals("Demo Employee",filteredPay.get(0).get("employeeName").asText());
        assertEquals(0,call("GET","/payroll-records?startPeriod=2026-02-01&endPeriod=2026-02-28",employee,null).size());
        request("GET","/payroll-records?employeeId="+hr,employee,null,403);
        // Verify duplicate/conflict protections (409)
        request("POST","/payroll-records",admin,Map.of("timesheetId",sheet,"regularRate",10000,"overtimeRate",15000,"deductions",0,"currency","INR"),409);
        request("POST","/training-modules/"+module+"/enroll",employee,null,409);
        request("POST","/applications",admin,Map.of("jobId",job,"candidateName","Another","email","another@example.test"),409);
        String openJob=postId("/job-postings",admin,Map.of("title","QA","status","PUBLISHED"));
        postId("/applications",admin,Map.of("jobId",openJob,"candidateName","Dup","email","dup@example.test"));
        request("POST","/applications",admin,Map.of("jobId",openJob,"candidateName","Dup","email","dup@example.test"),409);
        // Verify 400 validation and 404 missing records
        String missing=UUID.randomUUID().toString();
        request("POST","/timesheets/"+missing+"/approve",admin,null,404);
        request("POST","/leave-requests/"+missing+"/approve",admin,null,404);
        request("POST","/payroll-records/"+missing+"/approve",admin,null,404);
        request("POST","/training-modules/"+missing+"/enroll",employee,null,404);
        request("GET","/meetings/"+missing+"/join",employee,null,404);
        request("PATCH","/applications/"+missing,admin,Map.of("stage","IN_REVIEW"),404);
        request("PATCH","/goals/"+goal,employee,Map.of("progressPercent",150),400);
        request("PATCH","/me/location",employee,Map.of("location","MOON"),400);
        request("POST","/leave-requests",employee,Map.of("startDate","2026-03-10","endDate","2026-03-05","type","VACATION"),400);
        // Verify uninvited employee gets 403 on meeting join
        String hrOnlyMeeting=postId("/meetings",admin,Map.of("title","HR Sync","durationMinutes",15,"startsAt","2026-12-13T09:00:00Z","joinUrl","https://example.org/hr","attendeeIds",List.of(hr)));
        request("GET","/meetings/"+hrOnlyMeeting+"/join",employee,null,403);
        // Verify disabling employee revokes active session
        String personToken=call("POST","/auth/login",null,Map.of("email","person@example.test","password","ExamplePass123")).get("accessToken").asText();
        call("GET","/me",personToken,null);
        call("PATCH","/employees/"+newEmployee,admin,Map.of("employmentStatus","INACTIVE"));
        request("GET","/me",personToken,null,401);
        // Verify bad credentials and unauthenticated access return 401
        request("POST","/auth/login",null,Map.of("email","employee@hrm.local","password","WrongPass"),401);
        request("GET","/me",null,null,401);
        // Verify static /hrm/ redirect and page are accessible without auth
        assertEquals(302,mvc.perform(MockMvcRequestBuilders.get("/hrm/")).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(MockMvcRequestBuilders.get("/hrm/index.html")).andReturn().getResponse().getStatus());
        request("POST","/auth/logout",employee,null,204);
        request("GET","/me",employee,null,401);
    }
}

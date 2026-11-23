package com.kanishk.hrm;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@Transactional
public class Attendance {
    private final Store s;
    private final ZoneId zone;
    private final BigDecimal dailyLimit;
    Attendance(Store s,@Value("${hrm.zone}") String zone,@Value("${hrm.daily-regular-hours}") BigDecimal limit){
        this.s=s;this.zone=ZoneId.of(zone);this.dailyLimit=limit;
        if(limit.signum()<=0 || limit.compareTo(BigDecimal.valueOf(24))>0)throw new IllegalArgumentException("daily regular hours");
    }
    @GetMapping("/time-sessions")
    public Object sessions(@RequestParam(required=false) UUID employeeId,
                           @RequestParam(required=false) String date,
                           @RequestParam(required=false) String from,
                           @RequestParam(required=false) String to) {
        if (!s.isHrOrAdmin()) {
            if (employeeId != null) s.owner(employeeId);
            employeeId = s.employee();
        }
        StringBuilder sql = new StringBuilder("select * from hr_session where 1=1");
        List<Object> args = new ArrayList<>();
        if (employeeId != null) {
            sql.append(" and employee_id=?");
            args.add(employeeId);
        }
        if (date != null) {
            LocalDate day = LocalDate.parse(date);
            sql.append(" and clock_in_at>=? and clock_in_at<?");
            args.add(day.atStartOfDay(zone).toOffsetDateTime());
            args.add(day.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        } else {
            LocalDate first = from != null ? LocalDate.parse(from) : null;
            LocalDate last = to != null ? LocalDate.parse(to) : null;
            if (first != null && last != null && last.isBefore(first)) throw new IllegalArgumentException("period");
            if (first != null) {
                sql.append(" and clock_in_at>=?");
                args.add(first.atStartOfDay(zone).toOffsetDateTime());
            }
            if (last != null) {
                sql.append(" and clock_in_at<?");
                args.add(last.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
            }
        }
        return s.rows(sql + " order by clock_in_at desc", args.toArray());
    }
    @GetMapping("/timesheets/report")
    public Object report(@RequestParam String from, @RequestParam String to,
                         @RequestParam(required=false) UUID employeeId) {
        LocalDate first = LocalDate.parse(from);
        LocalDate last = LocalDate.parse(to);
        if (last.isBefore(first)) throw new IllegalArgumentException("period");
        if (!s.isHrOrAdmin()) {
            if (employeeId != null) s.owner(employeeId);
            employeeId = s.employee();
        }
        String sql = "select w.*,e.full_name,coalesce(t.status,'UNSUBMITTED') as approval_status"
                + " from hr_session w join hr_employee e on e.id=w.employee_id"
                + " left join hr_sheet t on t.id=w.timesheet_id where w.clock_in_at>=? and w.clock_in_at<?";
        List<Object> args = new ArrayList<>();
        args.add(first.atStartOfDay(zone).toOffsetDateTime());
        args.add(last.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        if (employeeId != null) {
            sql += " and w.employee_id=?";
            args.add(employeeId);
        }
        var records = s.rows(sql + " order by w.clock_in_at,e.full_name", args.toArray());
        Set<LocalDate> daysWorked = new HashSet<>();
        long closedSeconds = 0, approvedSeconds = 0, pendingSeconds = 0;
        for (var record : records) {
            var clockOut = (OffsetDateTime)record.get("clockOutAt");
            record.put("hours", null);
            if (clockOut == null) continue;
            var clockIn = (OffsetDateTime)record.get("clockInAt");
            long duration = Duration.between(clockIn, clockOut).getSeconds();
            record.put("hours", hours(duration, 1));
            daysWorked.add(clockIn.atZoneSameInstant(zone).toLocalDate());
            closedSeconds += duration;
            if ("APPROVED".equals(record.get("approvalStatus"))) approvedSeconds += duration;
            if ("PENDING".equals(record.get("approvalStatus"))) pendingSeconds += duration;
        }
        return Map.of("records",records,"totalHours",hours(closedSeconds,1),
                "approvedHours",hours(approvedSeconds,1),"pendingHours",hours(pendingSeconds,1),
                "averageHoursPerDay",hours(closedSeconds,Math.max(1,daysWorked.size())));
    }

    private static BigDecimal hours(long seconds, int days) {
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600L * days), 4, RoundingMode.HALF_UP);
    }
    @PostMapping("/time-sessions/clock-in")
    public Object clockIn(@RequestBody(required=false) Fields f){
        s.lockEmployee();UUID employee=s.employee();
        if(!s.rows("select id from hr_session where employee_id=? and clock_out_at is null",employee).isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Already clocked in");
        Object location=s.one("select location from hr_employee where id=?",employee).get("location");
        if(f!=null && f.containsKey("location")){location=f.choice("location","REMOTE","ONSITE");s.db.update("update hr_employee set location=? where id=?",location,employee);}
        return s.insert("session","employee_id,clock_in_at,location,status",employee,OffsetDateTime.now(),location,"OPEN");
    }
    @PatchMapping("/me/location") public Object location(@RequestBody Fields f){
        s.lockEmployee(); UUID id=s.employee();String location=f.choice("location","REMOTE","ONSITE");
        s.db.update("update hr_employee set location=? where id=?",location,id);
        s.db.update("update hr_session set location=? where employee_id=? and status='OPEN'",location,id);
        return Map.of("location",location);
    }
    @PostMapping("/time-sessions/{id}/clock-out") public Object clockOut(@PathVariable UUID id){
        s.lockEmployee();s.owner(s.one("select employee_id from hr_session where id=?",id).get("employeeId"));
        if(s.db.update("update hr_session set status='CLOSED',clock_out_at=CURRENT_TIMESTAMP where id=? and status='OPEN'",id)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Session already closed");
        return s.one("select * from hr_session where id=?",id);
    }
    @GetMapping("/timesheets") public Object sheets(){return s.scoped("sheet");}
    @PostMapping("/timesheets") public Object submit(@RequestBody Fields f){
        s.lockEmployee();LocalDate start=f.date("startPeriod"),end=f.date("endPeriod");
        if(end.isBefore(start))throw new IllegalArgumentException("period");
        UUID employee=s.employee();
        var sessions=s.rows("select * from hr_session where employee_id=? and clock_in_at>=? and clock_in_at<? and timesheet_id is null order by clock_in_at",employee,start.atStartOfDay(zone).toOffsetDateTime(),end.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        if(sessions.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"No unsubmitted sessions");
        if(!s.rows("select id from hr_sheet where employee_id=? and status='PENDING' and start_period<=? and end_period>=?",employee,end,start).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Review the existing overlapping timesheet first");
        Map<LocalDate,Long> seconds=new HashMap<>();
        for(var row:sessions){
            OffsetDateTime from=(OffsetDateTime)row.get("clockInAt"),to=(OffsetDateTime)row.get("clockOutAt");
            if(to==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Clock out before submitting");
            seconds.merge(from.atZoneSameInstant(zone).toLocalDate(),Duration.between(from,to).getSeconds(),Long::sum);
        }
        BigDecimal regular=BigDecimal.ZERO, overtime=BigDecimal.ZERO;
        for(var entry:seconds.entrySet()){
            LocalDate day=entry.getKey();
            var prior=s.rows("select clock_in_at,clock_out_at from hr_session where employee_id=? and timesheet_id is not null and clock_in_at>=? and clock_in_at<?",
                employee,day.atStartOfDay(zone).toOffsetDateTime(),day.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
            long priorSeconds=0;
            for(var row:prior)priorSeconds+=Duration.between((OffsetDateTime)row.get("clockInAt"),(OffsetDateTime)row.get("clockOutAt")).getSeconds();
            BigDecimal available=dailyLimit.subtract(BigDecimal.valueOf(priorSeconds).divide(BigDecimal.valueOf(3600),4,RoundingMode.HALF_UP)).max(BigDecimal.ZERO);
            BigDecimal hours=BigDecimal.valueOf(entry.getValue()).divide(BigDecimal.valueOf(3600),4,RoundingMode.HALF_UP);
            regular=regular.add(hours.min(available));overtime=overtime.add(hours.subtract(available).max(BigDecimal.ZERO));
        }
        var sheet=s.insert("sheet","employee_id,start_period,end_period,total_regular_hours,overtime_hours,status",employee,start,end,regular,overtime,"PENDING");
        for(var row:sessions)s.db.update("update hr_session set timesheet_id=? where id=?",sheet.get("id"),row.get("id"));
        return sheet;
    }
    @PostMapping("/timesheets/{id}/{decision:approve|reject}") @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public Object review(@PathVariable UUID id,@PathVariable String decision){
        var sheet=s.transition("sheet",id,"PENDING",decision.equals("approve")?"APPROVED":"REJECTED",true);
        if(decision.equals("reject"))s.db.update("update hr_session set timesheet_id=null where timesheet_id=?",id);
        return sheet;
    }
    @GetMapping(value="/timesheets/{id}/export",produces="text/csv") public ResponseEntity<String> export(@PathVariable UUID id){
        var sheet=s.one("select * from hr_sheet where id=?",id);if(!s.isHrOrAdmin())s.owner(sheet.get("employeeId"));
        String csv="clockInAt,clockOutAt,location\r\n"+s.rows("select * from hr_session where timesheet_id=? order by clock_in_at",id).stream().map(r->r.get("clockInAt")+","+r.get("clockOutAt")+","+r.get("location")).collect(Collectors.joining("\r\n"));
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=timesheet-"+id+".csv").body(csv);
    }
    @GetMapping("/payroll-records")
    public Object payroll(@RequestParam(required=false) String startPeriod,
                          @RequestParam(required=false) String endPeriod,
                          @RequestParam(required=false) UUID employeeId) {
        if (!s.isHrOrAdmin()) {
            if (employeeId != null) s.owner(employeeId);
            employeeId = s.employee();
        }
        LocalDate start = startPeriod != null ? LocalDate.parse(startPeriod) : null;
        LocalDate end = endPeriod != null ? LocalDate.parse(endPeriod) : null;
        if (start != null && end != null && end.isBefore(start)) throw new IllegalArgumentException("period");
        StringBuilder sql = new StringBuilder("select p.*,e.full_name as employee_name,s.start_period,s.end_period,s.status as timesheet_status,"
                + "(select count(*) from hr_session w where w.timesheet_id=s.id) as clock_ins,"
                + "(select count(w.clock_out_at) from hr_session w where w.timesheet_id=s.id) as clock_outs"
                + " from hr_payroll p join hr_sheet s on s.id=p.timesheet_id join hr_employee e on e.id=p.employee_id where 1=1");
        List<Object> args = new ArrayList<>();
        if (employeeId != null) {
            sql.append(" and p.employee_id=?");
            args.add(employeeId);
        }
        if (start != null) {
            sql.append(" and s.end_period>=?");
            args.add(start);
        }
        if (end != null) {
            sql.append(" and s.start_period<=?");
            args.add(end);
        }
        return s.rows(sql + " order by s.start_period desc", args.toArray());
    }
    @PostMapping("/payroll-records") @PreAuthorize("hasRole('ADMIN')") public Object calculate(@RequestBody Fields f){
        var sheet=s.one("select * from hr_sheet where id=? for update",f.id("timesheetId"));
        if(!"APPROVED".equals(sheet.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Approve the timesheet first");
        BigDecimal regular=(BigDecimal)sheet.get("totalRegularHours"),ot=(BigDecimal)sheet.get("overtimeHours");
        long rate=f.number("regularRate",0,Integer.MAX_VALUE),otRate=f.number("overtimeRate",0,Integer.MAX_VALUE);
        long gross=regular.multiply(BigDecimal.valueOf(rate)).add(ot.multiply(BigDecimal.valueOf(otRate))).setScale(0,RoundingMode.HALF_UP).longValueExact();
        long deductions=f.number("deductions",0,gross);String currency=Currency.getInstance(f.text("currency")).getCurrencyCode();
        var record=s.insert("payroll","timesheet_id,employee_id,regular_hours,overtime_hours,regular_rate,overtime_rate,gross_pay,deductions,net_pay,currency,status",sheet.get("id"),sheet.get("employeeId"),regular,ot,rate,otRate,gross,deductions,gross-deductions,currency,"PENDING");
        s.audit("payroll",(UUID)record.get("id"),"CALCULATED");
        return record;
    }
    @PostMapping("/payroll-records/{id}/approve") @PreAuthorize("hasRole('ADMIN')") public Object approve(@PathVariable UUID id){return s.transition("payroll",id,"PENDING","APPROVED",true);}
    @GetMapping("/approvals") @PreAuthorize("hasAnyRole('ADMIN','HR')") public Object approvals(){return s.rows("select * from hr_approval order by actioned_at desc");}
    @GetMapping("/audit-events") @PreAuthorize("hasRole('ADMIN')")
    public Object auditEvents(){return s.rows("select * from hr_audit order by created_at desc,id");}
}

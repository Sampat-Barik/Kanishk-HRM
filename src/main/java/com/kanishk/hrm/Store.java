package com.kanishk.hrm;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Component
class Store {
    final JdbcTemplate db;
    Store(JdbcTemplate db) { this.db = db; }

    List<Map<String,Object>> rows(String sql, Object... args) {
        return db.query(sql, (rs, index) -> {
            Map<String,Object> row = new LinkedHashMap<>();
            for (int i=1; i<=rs.getMetaData().getColumnCount(); i++) {
                String[] parts = rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT).split("_");
                String key = parts[0];
                for (int j=1; j<parts.length; j++) key += Character.toUpperCase(parts[j].charAt(0)) + parts[j].substring(1);
                Object value = rs.getObject(i);
                if (rs.getMetaData().getColumnType(i)==Types.TIMESTAMP_WITH_TIMEZONE
                        || "timestamptz".equalsIgnoreCase(rs.getMetaData().getColumnTypeName(i))) {
                    value = rs.getObject(i, OffsetDateTime.class);
                }
                row.put(key, value);
            }
            return row;
        }, args);
    }
    Map<String,Object> one(String sql, Object... args) {
        return rows(sql,args).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found"));
    }
    UUID user() { return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()); }
    boolean admin() { return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_HR_ADMIN")); }
    UUID employee() { return (UUID)one("select id from hr_employee where user_id=?",user()).get("id"); }
    void lockEmployee() { one("select id from hr_employee where user_id=? for update",user()); }
    void owner(Object id) { if (!employee().equals(id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    Map<String,Object> insert(String table, String columns, Object... values) {
        UUID id = UUID.randomUUID();
        List<Object> args = new ArrayList<>(); args.add(id); Collections.addAll(args,values);
        db.update("insert into hr_"+table+" (id,"+columns+") values ("+String.join(",",Collections.nCopies(args.size(),"?"))+")",args.toArray());
        return one("select * from hr_"+table+" where id=?",id);
    }
    List<Map<String,Object>> scoped(String table) {
        String sql="select * from hr_"+table;
        return admin()?rows(sql):rows(sql+" where employee_id=?",employee());
    }
    Map<String,Object> transition(String table, UUID id, String before, String after, boolean log) {
        one("select id from hr_"+table+" where id=? for update",id);
        int changed=db.update("update hr_"+table+" set status=? where id=? and status=?",after,id,before);
        if (changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid current status");
        if (log) insert("approval","entity_type,entity_id,status,reviewer_id,actioned_at",table,id,after,user(),OffsetDateTime.now());
        audit(table,id,after);
        return one("select * from hr_"+table+" where id=?",id);
    }
    void audit(String entity, UUID id, String action) {
        db.update("insert into hr_audit (id,actor_id,entity_type,entity_id,action,created_at) values (?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID(),user(),entity,id,action);
    }
}
@RestControllerAdvice
class Errors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> status(ResponseStatusException ex){
        String message=ex.getReason()!=null?ex.getReason():ex.getStatus().getReasonPhrase();
        return ResponseEntity.status(ex.getStatus()).body(Map.of("message",message));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,String> conflict(){return Map.of("message","Duplicate, missing reference or invalid field length");}
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class,ArithmeticException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> invalid(RuntimeException ex){return Map.of("message","Invalid request field or value");}
}

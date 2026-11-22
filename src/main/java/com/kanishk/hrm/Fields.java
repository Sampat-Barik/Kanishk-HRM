package com.kanishk.hrm;

import java.time.*;
import java.util.*;
import java.net.URI;
import java.math.BigDecimal;

class Fields extends LinkedHashMap<String,Object> {
    String text(String key) {
        Object value=get(key);
        if (!(value instanceof String) || ((String)value).isBlank()) throw new IllegalArgumentException(key);
        return ((String)value).trim();
    }
    String optional(String key){return get(key)==null?null:text(key);}
    String email(){String email=text("email").toLowerCase(Locale.ROOT); if(!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("email"); return email;}
    String choice(String key,String... allowed){String value=text(key).toUpperCase(Locale.ROOT); if(!List.of(allowed).contains(value)) throw new IllegalArgumentException(key);return value;}
    UUID id(String key){return UUID.fromString(text(key));}
    LocalDate date(String key){return LocalDate.parse(text(key));}
    OffsetDateTime instant(String key){return OffsetDateTime.parse(text(key));}
    long number(String key,long min,long max){
        long value=new BigDecimal(String.valueOf(get(key))).longValueExact();
        if(value<min || value>max)throw new IllegalArgumentException(key);
        return value;
    }
    boolean bool(String key){if(!(get(key) instanceof Boolean))throw new IllegalArgumentException(key);return (boolean)get(key);}
    String url(String key){URI uri=URI.create(text(key));if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null)throw new IllegalArgumentException(key);return uri.toString();}
}

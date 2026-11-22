package com.kanishk.hrm;

import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
class WebHome {
    @GetMapping({"/", "/hrm", "/hrm/"})
    String home() {
        return "redirect:/hrm/index.html";
    }

    @GetMapping("/api/health")
    @ResponseBody
    Map<String, String> health() {
        return Map.of("status", "ok");
    }
}

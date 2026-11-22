package com.kanishk.hrm;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class WebHome {
    @GetMapping({"/", "/hrm", "/hrm/"})
    String home() {
        return "redirect:/hrm/index.html";
    }
}

package com.novel.agent.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class CostPanelController {

    @GetMapping("/cost-panel")
    public String panel() {
        return "forward:/cost-dashboard.html";
    }
}

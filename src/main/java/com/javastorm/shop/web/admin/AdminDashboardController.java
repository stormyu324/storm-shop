package com.javastorm.shop.web.admin;

import com.javastorm.shop.service.DashboardService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminDashboardController {

    private final DashboardService dashboard;

    public AdminDashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/admin")
    public String dashboard(Model model) {
        DashboardService.Stats s = dashboard.stats();
        model.addAttribute("onSale", s.onSale());
        model.addAttribute("drafts", s.drafts());
        model.addAttribute("offShelf", s.offShelf());
        model.addAttribute("lowStock", s.lowStock());
        model.addAttribute("pendingOrders", s.ordersToFulfil());
        model.addAttribute("lowStockThreshold", s.lowStockThreshold());
        return "admin/dashboard";
    }
}

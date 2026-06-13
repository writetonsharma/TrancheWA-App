package com.tranche.bakery.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("dashboard", adminService.buildDashboard());
        return "admin/dashboard";
    }

    @PostMapping("/orders/{id}/approve-payment")
    public String approvePayment(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        adminService.approvePayment(id);
        redirectAttributes.addFlashAttribute("flash", "Payment approved and customer notified.");
        return "redirect:/admin";
    }

    @PostMapping("/orders/{id}/flag-payment")
    public String flagPayment(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        adminService.flagForReview(id);
        redirectAttributes.addFlashAttribute("flash", "Order flagged for review.");
        return "redirect:/admin";
    }

    @PostMapping("/message/send")
    public String sendMessage(@RequestParam String phone,
                              @RequestParam String message,
                              RedirectAttributes redirectAttributes) {
        adminService.sendMessage(phone, message);
        redirectAttributes.addFlashAttribute("flash", "Message sent to " + phone + ".");
        return "redirect:/admin";
    }
}

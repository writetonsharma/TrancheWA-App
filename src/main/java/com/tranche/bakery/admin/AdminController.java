package com.tranche.bakery.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    public String dashboard(@RequestParam(required = false) String search, Model model) {
        model.addAttribute("dashboard", adminService.buildDashboard());
        if (search != null && !search.isBlank()) {
            model.addAttribute("searchQuery", search);
            model.addAttribute("searchResults", adminService.searchOrders(search));
        }
        return "admin/dashboard";
    }

    @GetMapping("/orders/{id}/qr")
    @ResponseBody
    public ResponseEntity<byte[]> viewQr(@PathVariable Long id) {
        byte[] qrImage = adminService.getQrImage(id);
        if (qrImage == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(qrImage);
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

    @PostMapping("/orders/{id}/mark-baking")
    public String markInBaking(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        adminService.markInBaking(id);
        redirectAttributes.addFlashAttribute("flash", "Order marked as In Baking — customer notified.");
        return "redirect:/admin";
    }

    @PostMapping("/orders/{id}/complete")
    public String markCompleted(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        adminService.markCompleted(id);
        redirectAttributes.addFlashAttribute("flash", "Order #" + id + " marked as delivered — customer notified.");
        return "redirect:/admin";
    }

    @PostMapping("/orders/{id}/cancel")
    public String cancelOrder(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        adminService.cancelOrder(id);
        redirectAttributes.addFlashAttribute("flash", "Order #" + id + " cancelled.");
        return "redirect:/admin";
    }

    @PostMapping("/alerts/resolve-all")
    public String resolveAllAlerts(RedirectAttributes redirectAttributes) {
        adminService.resolveAllAlerts();
        redirectAttributes.addFlashAttribute("flash", "All alerts marked as resolved.");
        return "redirect:/admin";
    }

    @GetMapping("/conversation/{phone}")
    public String viewConversation(@PathVariable String phone, Model model) {
        ConversationThread thread = adminService.getConversation(phone);
        if (thread == null) {
            return "redirect:/admin";
        }
        model.addAttribute("thread", thread);
        return "admin/conversation";
    }

    @PostMapping("/conversation/{phone}/send")
    public String sendConversationMessage(@PathVariable String phone,
                                          @RequestParam String message,
                                          RedirectAttributes redirectAttributes) {
        adminService.sendMessage(phone, message);
        redirectAttributes.addFlashAttribute("flash", "Message sent.");
        return "redirect:/admin/conversation/" + phone;
    }

    @PostMapping("/conversation/{phone}/update-details")
    public String updateCustomerDetails(@PathVariable String phone,
                                        @RequestParam(required = false) String name,
                                        @RequestParam(required = false) String deliveryArea,
                                        @RequestParam(required = false) String deliveryAddress,
                                        @RequestParam(required = false) String locationLat,
                                        @RequestParam(required = false) String locationLng,
                                        RedirectAttributes redirectAttributes) {
        adminService.updateCustomerDetails(phone, name, deliveryArea, deliveryAddress, locationLat, locationLng);
        redirectAttributes.addFlashAttribute("flash", "Customer details updated.");
        return "redirect:/admin/conversation/" + phone;
    }
}

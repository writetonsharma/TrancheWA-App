package com.tranche.bakery.admin;

import com.tranche.bakery.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

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

    private static final int ORDERS_PAGE_SIZE = 25;

    @GetMapping("/orders")
    public String orders(@RequestParam(required = false) String from,
                         @RequestParam(required = false) String to,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) String customer,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        OrderStatus statusFilter = parseStatus(status);

        model.addAttribute("orderPage",
                adminService.findOrders(fromDate, toDate, statusFilter, customer, page, ORDERS_PAGE_SIZE));
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("allStatuses", OrderStatus.values());
        // Echo filters back so the form keeps its values and paging links carry them
        model.addAttribute("fFrom", from);
        model.addAttribute("fTo", to);
        model.addAttribute("fStatus", status);
        model.addAttribute("fCustomer", customer);
        return "admin/orders";
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private OrderStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OrderStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @GetMapping("/orders/export")
    @ResponseBody
    public ResponseEntity<byte[]> exportOrders(@RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String customer) {
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        OrderStatus statusFilter = parseStatus(status);

        String csv = adminService.exportOrdersCsv(fromDate, toDate, statusFilter, customer);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=orders-export.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
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
    public String viewConversation(@PathVariable String phone,
                                   @RequestParam(required = false) String orderStatus,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   Model model) {
        OrderStatus statusFilter = parseStatus(orderStatus);
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);

        ConversationThread thread = adminService.getConversation(phone, statusFilter, fromDate, toDate);
        if (thread == null) {
            return "redirect:/admin";
        }
        model.addAttribute("thread", thread);
        model.addAttribute("allStatuses", OrderStatus.values());
        model.addAttribute("historyStatus", orderStatus);
        model.addAttribute("historyFrom", from);
        model.addAttribute("historyTo", to);
        model.addAttribute("historyFiltered", statusFilter != null || fromDate != null || toDate != null);
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

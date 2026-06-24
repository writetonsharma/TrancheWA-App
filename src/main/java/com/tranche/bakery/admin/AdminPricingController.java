package com.tranche.bakery.admin;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/pricing")
public class AdminPricingController {

    private final CustomerRepository customerRepository;

    @GetMapping
    public String pricingOverrides(Model model) {
        List<Customer> overrides = customerRepository.findAllWithPricingOverride();
        model.addAttribute("overrides", overrides);
        model.addAttribute("now", LocalDateTime.now());
        return "admin/pricing";
    }

    @PostMapping("/set")
    public String setOverride(@RequestParam String phone,
                              @RequestParam BigDecimal amount,
                              @RequestParam(required = false) boolean freeDelivery,
                              @RequestParam(required = false) Integer expiryDays,
                              @RequestParam(required = false) String note,
                              RedirectAttributes redirectAttributes) {
        Customer customer = customerRepository.findByPhone(phone).orElse(null);
        if (customer == null) {
            redirectAttributes.addFlashAttribute("error",
                    "Customer with phone " + phone + " not found. They must message the bot at least once before you can set pricing.");
            return "redirect:/admin/pricing";
        }

        customer.setPricingOverride(amount);
        customer.setFreeDelivery(freeDelivery);
        customer.setOverrideNote(note);
        if (expiryDays != null && expiryDays > 0) {
            customer.setOverrideExpiresAt(LocalDateTime.now().plusDays(expiryDays));
        } else {
            customer.setOverrideExpiresAt(null);
        }
        customerRepository.save(customer);

        redirectAttributes.addFlashAttribute("flash",
                "Pricing override set for " + phone + ": flat rate ₹" + amount +
                (freeDelivery ? " + free delivery" : "") +
                (expiryDays != null && expiryDays > 0 ? " (expires in " + expiryDays + " days)" : " (no expiry)"));
        return "redirect:/admin/pricing";
    }

    @PostMapping("/remove/{id}")
    public String removeOverride(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Customer customer = customerRepository.findById(id).orElse(null);
        if (customer == null) {
            redirectAttributes.addFlashAttribute("error", "Customer not found.");
            return "redirect:/admin/pricing";
        }

        customer.setPricingOverride(null);
        customer.setFreeDelivery(false);
        customer.setOverrideExpiresAt(null);
        customer.setOverrideNote(null);
        customerRepository.save(customer);

        redirectAttributes.addFlashAttribute("flash",
                "Pricing override removed for " + customer.getPhone());
        return "redirect:/admin/pricing";
    }
}

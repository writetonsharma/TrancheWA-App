package com.tranche.bakery.admin;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.menu.MenuCategoryRepository;
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
    private final MenuCategoryRepository menuCategoryRepository;

    @GetMapping
    public String pricingOverrides(Model model) {
        List<Customer> overrides = customerRepository.findAllWithPricingOverride();
        model.addAttribute("overrides", overrides);
        model.addAttribute("categories", menuCategoryRepository.findAllByActiveTrueOrderByDisplayOrderAsc());
        model.addAttribute("now", LocalDateTime.now());
        return "admin/pricing";
    }

    @PostMapping("/set")
    public String setOverride(@RequestParam String phone,
                              @RequestParam(required = false) BigDecimal amount,
                              @RequestParam(required = false) boolean freeDelivery,
                              @RequestParam(required = false) Integer expiryDays,
                              @RequestParam(required = false) String note,
                              @RequestParam(required = false) List<String> categoryNames,
                              @RequestParam(required = false) List<String> categoryPrices,
                              RedirectAttributes redirectAttributes) {
        Customer customer = customerRepository.findByPhone(phone).orElse(null);
        if (customer == null) {
            redirectAttributes.addFlashAttribute("error",
                    "Customer with phone " + phone + " not found. They must message the bot at least once before you can set pricing.");
            return "redirect:/admin/pricing";
        }

        // Zip the parallel category name/price inputs; a blank price clears that category.
        customer.getCategoryPrices().clear();
        if (categoryNames != null && categoryPrices != null) {
            for (int i = 0; i < categoryNames.size() && i < categoryPrices.size(); i++) {
                String priceRaw = categoryPrices.get(i);
                if (priceRaw == null || priceRaw.isBlank()) continue;
                try {
                    customer.getCategoryPrices().put(categoryNames.get(i), new BigDecimal(priceRaw.trim()));
                } catch (NumberFormatException ignored) {
                    // skip an unparseable value rather than fail the whole save
                }
            }
        }

        if (amount == null && customer.getCategoryPrices().isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Enter an all-items flat rate and/or at least one category price for " + phone + ".");
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
                "Pricing saved for " + phone + " — " + describe(customer) +
                (freeDelivery ? " + free delivery" : "") +
                (expiryDays != null && expiryDays > 0 ? " (expires in " + expiryDays + " days)" : " (no expiry)"));
        return "redirect:/admin/pricing";
    }

    private String describe(Customer customer) {
        StringBuilder sb = new StringBuilder();
        if (customer.getPricingOverride() != null) {
            sb.append("all items ₹").append(customer.getPricingOverride());
        }
        customer.getCategoryPrices().forEach((cat, price) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(cat).append(" ₹").append(price);
        });
        return sb.length() > 0 ? sb.toString() : "no flat pricing";
    }

    @PostMapping("/remove/{id}")
    public String removeOverride(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Customer customer = customerRepository.findById(id).orElse(null);
        if (customer == null) {
            redirectAttributes.addFlashAttribute("error", "Customer not found.");
            return "redirect:/admin/pricing";
        }

        customer.setPricingOverride(null);
        customer.getCategoryPrices().clear();
        customer.setFreeDelivery(false);
        customer.setOverrideExpiresAt(null);
        customer.setOverrideNote(null);
        customerRepository.save(customer);

        redirectAttributes.addFlashAttribute("flash",
                "Pricing override removed for " + customer.getPhone());
        return "redirect:/admin/pricing";
    }
}

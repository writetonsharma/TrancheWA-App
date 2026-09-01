package com.tranche.bakery.admin;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.customer.FriendsFamilyPricing;
import com.tranche.bakery.menu.MenuCategory;
import com.tranche.bakery.menu.MenuCategoryRepository;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/pricing")
public class AdminPricingController {

    private final CustomerRepository customerRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final MenuItemRepository menuItemRepository;
    private final FriendsFamilyPricing friendsFamilyPricing;

    @GetMapping
    public String pricingOverrides(Model model) {
        List<Customer> overrides = customerRepository.findAllWithPricingOverride();
        List<MenuCategory> categories = menuCategoryRepository.findAllByActiveTrueOrderByDisplayOrderAsc();
        // Active items grouped under each category, in display order, for the per-item price inputs.
        Map<MenuCategory, List<MenuItem>> itemsByCategory = new LinkedHashMap<>();
        for (MenuCategory cat : categories) {
            itemsByCategory.put(cat, menuItemRepository.findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(cat));
        }
        model.addAttribute("overrides", overrides);
        model.addAttribute("itemsByCategory", itemsByCategory);
        model.addAttribute("now", LocalDateTime.now());
        return "admin/pricing";
    }

    @PostMapping("/set")
    public String setOverride(@RequestParam String phone,
                              @RequestParam(required = false) BigDecimal amount,
                              @RequestParam(required = false) boolean freeDelivery,
                              @RequestParam(required = false) boolean subscriptionEligible,
                              @RequestParam(required = false) Integer expiryDays,
                              @RequestParam(required = false) String note,
                              @RequestParam(required = false) List<String> categoryNames,
                              @RequestParam(required = false) List<String> categoryPrices,
                              @RequestParam(required = false) List<String> itemNames,
                              @RequestParam(required = false) List<String> itemPrices,
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

        // Zip the parallel item name/price inputs; a blank price clears that item.
        customer.getItemPrices().clear();
        if (itemNames != null && itemPrices != null) {
            for (int i = 0; i < itemNames.size() && i < itemPrices.size(); i++) {
                String priceRaw = itemPrices.get(i);
                if (priceRaw == null || priceRaw.isBlank()) continue;
                try {
                    customer.getItemPrices().put(itemNames.get(i), new BigDecimal(priceRaw.trim()));
                } catch (NumberFormatException ignored) {
                    // skip an unparseable value rather than fail the whole save
                }
            }
        }

        if (amount == null && customer.getCategoryPrices().isEmpty() && customer.getItemPrices().isEmpty()
                && !subscriptionEligible) {
            redirectAttributes.addFlashAttribute("error",
                    "Enter an all-items flat rate, at least one category or item price, or tick subscription access for " + phone + ".");
            return "redirect:/admin/pricing";
        }

        customer.setPricingOverride(amount);
        customer.setFreeDelivery(freeDelivery);
        customer.setSubscriptionEligible(subscriptionEligible);
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

    /** One-click apply of the F&F rate card from friends-family-pricing.json to a customer. */
    @PostMapping("/apply-preset")
    public String applyPreset(@RequestParam String phone,
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

        customer.getCategoryPrices().clear();
        customer.getCategoryPrices().putAll(friendsFamilyPricing.categoryPrices());
        customer.getItemPrices().clear();
        customer.getItemPrices().putAll(friendsFamilyPricing.itemPrices());
        customer.setPricingOverride(null);
        customer.setSubscriptionEligible(friendsFamilyPricing.subscriptionEligible());
        customer.setFreeDelivery(freeDelivery);
        customer.setOverrideNote(note != null && !note.isBlank() ? note : "F&F preset");
        if (expiryDays != null && expiryDays > 0) {
            customer.setOverrideExpiresAt(LocalDateTime.now().plusDays(expiryDays));
        } else {
            customer.setOverrideExpiresAt(null);
        }
        customerRepository.save(customer);

        redirectAttributes.addFlashAttribute("flash",
                "Applied F&F preset to " + phone + " — " + friendsFamilyPricing.size() + " prices"
                + (freeDelivery ? " + free delivery" : "")
                + (expiryDays != null && expiryDays > 0 ? " (expires in " + expiryDays + " days)" : " (no expiry)"));
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
        customer.getItemPrices().forEach((item, price) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(item).append(" ₹").append(price);
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
        customer.getItemPrices().clear();
        customer.setFreeDelivery(false);
        customer.setSubscriptionEligible(false);
        customer.setOverrideExpiresAt(null);
        customer.setOverrideNote(null);
        customerRepository.save(customer);

        redirectAttributes.addFlashAttribute("flash",
                "Pricing override removed for " + customer.getPhone());
        return "redirect:/admin/pricing";
    }
}

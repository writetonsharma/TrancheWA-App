package com.tranche.bakery.admin;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/customers")
public class AdminCustomersController {

    private final CustomerRepository customerRepository;

    @GetMapping
    public String list(@RequestParam(required = false) String q, Model model) {
        List<Customer> customers;
        if (q != null && !q.isBlank()) {
            customers = customerRepository.findByPhoneContainingOrNameContainingIgnoreCase(q.trim(), q.trim());
        } else {
            customers = customerRepository.findAllByOrderByCreatedAtDesc();
        }
        model.addAttribute("customers", customers);
        model.addAttribute("q", q);
        return "admin/customers";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        Customer customer = customerRepository.findById(id).orElse(null);
        if (customer == null) {
            redirectAttributes.addFlashAttribute("error", "Customer not found.");
            return "redirect:/admin/customers";
        }
        model.addAttribute("customer", customer);
        return "admin/customer-detail";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) String name,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) String deliveryArea,
                         @RequestParam(required = false) String deliveryAddress,
                         RedirectAttributes redirectAttributes) {
        Customer customer = customerRepository.findById(id).orElse(null);
        if (customer == null) {
            redirectAttributes.addFlashAttribute("error", "Customer not found.");
            return "redirect:/admin/customers";
        }

        // Phone is the WhatsApp identity key. Only accept a change to a number not already
        // in use by another customer, so we never merge two people onto one conversation.
        if (phone != null && !phone.isBlank() && !phone.trim().equals(customer.getPhone())) {
            String newPhone = phone.trim();
            var clash = customerRepository.findByPhone(newPhone).orElse(null);
            if (clash != null && !clash.getId().equals(customer.getId())) {
                redirectAttributes.addFlashAttribute("error",
                        "Phone " + newPhone + " already belongs to another customer.");
                return "redirect:/admin/customers/" + id;
            }
            customer.setPhone(newPhone);
        }

        customer.setName(blankToNull(name));
        customer.setDeliveryArea(blankToNull(deliveryArea));
        customer.setDeliveryAddress(blankToNull(deliveryAddress));
        customerRepository.save(customer);

        redirectAttributes.addFlashAttribute("flash", "Customer details updated.");
        return "redirect:/admin/customers/" + id;
    }

    // Adjust account credit: positive amount adds (e.g. an overpayment), negative removes (e.g. a
    // refund handled outside the app). Never goes below zero.
    @PostMapping("/{id}/credit")
    public String adjustCredit(@PathVariable Long id,
                               @RequestParam String amount,
                               RedirectAttributes redirectAttributes) {
        Customer customer = customerRepository.findById(id).orElse(null);
        if (customer == null) {
            redirectAttributes.addFlashAttribute("error", "Customer not found.");
            return "redirect:/admin/customers";
        }
        java.math.BigDecimal delta;
        try {
            delta = new java.math.BigDecimal(amount.trim());
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", "Enter a valid amount (e.g. 65 to add, -65 to remove).");
            return "redirect:/admin/customers/" + id;
        }
        java.math.BigDecimal bal = customer.getCreditBalance() == null
                ? java.math.BigDecimal.ZERO : customer.getCreditBalance();
        java.math.BigDecimal newBal = bal.add(delta);
        if (newBal.signum() < 0) newBal = java.math.BigDecimal.ZERO;
        customer.setCreditBalance(newBal);
        customerRepository.save(customer);
        redirectAttributes.addFlashAttribute("flash", "Credit updated. New balance: ₹" + newBal + ".");
        return "redirect:/admin/customers/" + id;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}

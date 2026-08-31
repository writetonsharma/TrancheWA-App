package com.tranche.bakery.admin;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.tranche.bakery.subscription.Subscription;
import com.tranche.bakery.subscription.SubscriptionRepository;
import com.tranche.bakery.subscription.SubscriptionService;
import com.tranche.bakery.subscription.SubscriptionStatus;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/subscriptions")
public class AdminSubscriptionsController {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;

    @GetMapping
    public String list(Model model) {
        List<Subscription> pending = subscriptionRepository.findAllByStatus(SubscriptionStatus.PENDING_PAYMENT);
        List<Subscription> active = subscriptionRepository.findAllByStatus(SubscriptionStatus.ACTIVE);
        model.addAttribute("pending", pending);
        model.addAttribute("active", active);
        return "admin/subscriptions";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        subscriptionService.activate(id);
        redirectAttributes.addFlashAttribute("flash", "Subscription #" + id + " activated — customer notified.");
        return "redirect:/admin/subscriptions";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        subscriptionService.cancel(id);
        redirectAttributes.addFlashAttribute("flash", "Subscription #" + id + " cancelled.");
        return "redirect:/admin/subscriptions";
    }
}

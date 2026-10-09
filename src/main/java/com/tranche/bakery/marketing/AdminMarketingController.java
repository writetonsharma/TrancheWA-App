package com.tranche.bakery.marketing;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/marketing")
public class AdminMarketingController {

    private final MarketingService marketingService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("customers", marketingService.audience());
        model.addAttribute("imageUrl", marketingService.defaultImageUrl());
        model.addAttribute("templateName", marketingService.templateName());
        return "admin/marketing";
    }

    @PostMapping("/send")
    public String send(@RequestParam(required = false) List<Long> customerIds,
                       @RequestParam(required = false) String imageUrl,
                       RedirectAttributes ra) {
        if (customerIds == null || customerIds.isEmpty()) {
            ra.addFlashAttribute("error", "Select at least one customer to message.");
            return "redirect:/admin/marketing";
        }
        MarketingService.BroadcastResult r = marketingService.broadcast(customerIds, imageUrl);
        ra.addFlashAttribute("flash", "Broadcast complete — " + r.sent() + " sent, "
                + r.skipped() + " skipped (opted out), " + r.failed() + " failed.");
        return "redirect:/admin/marketing";
    }
}

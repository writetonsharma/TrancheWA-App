package com.tranche.bakery.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.tranche.bakery.offer.Offer;
import com.tranche.bakery.offer.OfferRepository;

import lombok.RequiredArgsConstructor;

/**
 * Admin Offers page. Lets the baker pause/resume and tune offers instantly from the
 * dashboard with no deploy or git push. Adding a brand-new offer kind still needs
 * code; everything here is data-only management of existing offers.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/offers")
public class AdminOffersController {

    private final OfferRepository offerRepository;

    @GetMapping
    public String offers(Model model) {
        List<Offer> offers = offerRepository.findAllByOrderByDisplayOrderAsc();
        model.addAttribute("offers", offers);
        model.addAttribute("now", LocalDateTime.now());
        return "admin/offers";
    }

    @PostMapping("/toggle/{id}")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        Offer offer = offerRepository.findById(id).orElse(null);
        if (offer == null) {
            ra.addFlashAttribute("error", "Offer not found.");
            return "redirect:/admin/offers";
        }
        offer.setActive(!offer.isActive());
        offerRepository.save(offer);
        ra.addFlashAttribute("flash",
                "'" + offer.getLabel() + "' is now " + (offer.isActive() ? "ON" : "OFF") + ".");
        return "redirect:/admin/offers";
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) BigDecimal percent,
                         @RequestParam(required = false) BigDecimal thresholdAmount,
                         @RequestParam(required = false) String label,
                         @RequestParam(required = false) String giftLabel,
                         RedirectAttributes ra) {
        Offer offer = offerRepository.findById(id).orElse(null);
        if (offer == null) {
            ra.addFlashAttribute("error", "Offer not found.");
            return "redirect:/admin/offers";
        }
        if (label != null && !label.isBlank()) offer.setLabel(label.trim());
        switch (offer.getKind()) {
            case PERCENT_OFF -> { if (percent != null) offer.setPercent(percent); }
            case FREE_DELIVERY_OVER -> { if (thresholdAmount != null) offer.setThresholdAmount(thresholdAmount); }
            case FREE_ITEM_OVER -> {
                if (thresholdAmount != null) offer.setThresholdAmount(thresholdAmount);
                if (giftLabel != null && !giftLabel.isBlank()) offer.setGiftLabel(giftLabel.trim());
            }
        }
        offerRepository.save(offer);
        ra.addFlashAttribute("flash", "Updated '" + offer.getLabel() + "'.");
        return "redirect:/admin/offers";
    }
}

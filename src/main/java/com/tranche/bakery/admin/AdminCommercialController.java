package com.tranche.bakery.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.tranche.bakery.menu.MenuCategory;
import com.tranche.bakery.menu.MenuCategoryRepository;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.order.CommercialOrderService;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.SellerProfileType;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/commercial")
public class AdminCommercialController {

    private final CommercialOrderService commercialOrderService;
    private final MenuCategoryRepository menuCategoryRepository;
    private final MenuItemRepository menuItemRepository;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("orders", commercialOrderService.listCommercial());
        return "admin/commercial-list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        Map<MenuCategory, List<MenuItem>> itemsByCategory = new LinkedHashMap<>();
        for (MenuCategory cat : menuCategoryRepository.findAllByActiveTrueOrderByDisplayOrderAsc()) {
            itemsByCategory.put(cat, menuItemRepository.findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(cat));
        }
        model.addAttribute("itemsByCategory", itemsByCategory);
        model.addAttribute("today", LocalDate.now());
        return "admin/commercial-new";
    }

    @PostMapping("/new")
    public String create(@RequestParam String name,
                         @RequestParam String phone,
                         @RequestParam(required = false) String businessName,
                         @RequestParam(required = false) String deliveryDate,
                         @RequestParam(required = false) String deliveryAddress,
                         @RequestParam(required = false) String notes,
                         @RequestParam(required = false) String deliveryCharge,
                         @RequestParam(required = false, defaultValue = "COMPANY") String sellerProfile,
                         @RequestParam(required = false) List<Long> itemIds,
                         @RequestParam(required = false) List<String> unitPrices,
                         @RequestParam(required = false) List<String> quantities,
                         @RequestParam(required = false) List<Long> complimentaryItemIds,
                         RedirectAttributes ra) {
        if (phone == null || phone.isBlank()) {
            ra.addFlashAttribute("error", "A phone number is required to create a commercial order.");
            return "redirect:/admin/commercial/new";
        }

        List<CommercialOrderService.Line> lines = new ArrayList<>();
        if (itemIds != null) {
            for (int i = 0; i < itemIds.size(); i++) {
                int qty = intAt(quantities, i);
                if (qty <= 0) continue;
                Long itemId = itemIds.get(i);
                boolean comp = complimentaryItemIds != null && complimentaryItemIds.contains(itemId);
                BigDecimal price = moneyAt(unitPrices, i);
                if (!comp && price == null) continue;
                lines.add(new CommercialOrderService.Line(itemId, price, qty, comp));
            }
        }
        if (lines.isEmpty()) {
            ra.addFlashAttribute("error", "Add at least one line with a quantity and unit price.");
            return "redirect:/admin/commercial/new";
        }

        Order order = commercialOrderService.createInvoice(
                name, phone, businessName, parseDate(deliveryDate), deliveryAddress, notes,
                moneyOrZero(deliveryCharge), parseSeller(sellerProfile), lines);

        ra.addFlashAttribute("flash",
                "Invoice " + order.getInvoiceNumber() + " created — download it below and send to the buyer.");
        return "redirect:/admin/commercial";
    }

    @GetMapping("/{id}/invoice")
    @ResponseBody
    public ResponseEntity<byte[]> invoice(@PathVariable Long id) {
        byte[] pdf = commercialOrderService.invoicePdf(id);
        if (pdf == null) return ResponseEntity.notFound().build();
        Order order = commercialOrderService.find(id);
        String fname = "Invoice-" + (order != null && order.getInvoiceNumber() != null ? order.getInvoiceNumber() : id) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fname)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/{id}/receipt")
    @ResponseBody
    public ResponseEntity<byte[]> receipt(@PathVariable Long id) {
        byte[] pdf = commercialOrderService.receiptPdf(id);
        if (pdf == null) return ResponseEntity.notFound().build();
        Order order = commercialOrderService.find(id);
        String fname = "Receipt-" + (order != null && order.getInvoiceNumber() != null ? order.getInvoiceNumber() : id) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fname)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/{id}/mark-paid")
    public String markPaid(@PathVariable Long id, RedirectAttributes ra) {
        commercialOrderService.markPaid(id);
        ra.addFlashAttribute("flash", "Marked paid — order is on the bake list. Download the receipt and send it to the buyer.");
        return "redirect:/admin/commercial";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes ra) {
        commercialOrderService.cancel(id);
        ra.addFlashAttribute("flash", "Commercial order cancelled.");
        return "redirect:/admin/commercial";
    }

    private static int intAt(List<String> list, int i) {
        if (list == null || i >= list.size() || list.get(i) == null || list.get(i).isBlank()) return 0;
        try {
            return Integer.parseInt(list.get(i).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal moneyAt(List<String> list, int i) {
        if (list == null || i >= list.size() || list.get(i) == null || list.get(i).isBlank()) return null;
        try {
            return new BigDecimal(list.get(i).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal moneyOrZero(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static SellerProfileType parseSeller(String s) {
        if (s == null || s.isBlank()) return SellerProfileType.COMPANY;
        try {
            return SellerProfileType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SellerProfileType.COMPANY;
        }
    }
}

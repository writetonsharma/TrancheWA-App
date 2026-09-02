package com.tranche.bakery.subscription;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory catalog of subscription plans loaded from subscriptions.json at boot (like menu.json).
 * Plans are never persisted; a Subscription snapshots the chosen plan + items at signup, so editing
 * this file never affects in-flight prepaid subscriptions.
 */
@Component
@Slf4j
public class SubscriptionCatalog {

    // Tier order, low to high. A plan of a given tier can offer items of its tier and every tier below it.
    private static final List<String> TIER_ORDER = List.of("NORMAL", "PREMIUM");

    private final Map<String, PlanConfig> byCode = new LinkedHashMap<>();
    // type (LOAF/ROLL/SWEET) -> tier (NORMAL/PREMIUM) -> item names
    private final Map<String, Map<String, List<String>>> itemTiers;
    // Menu category -> pieces per pack (Buns & Rolls = 6, Sweet Bakes = 4); loaves default to 1.
    private final Map<String, Integer> packSizes;
    // Fallback delivery charge when a plan omits deliveryCharge (matches the à la carte order charge).
    private final BigDecimal standardDeliveryCharge;

    public SubscriptionCatalog(ObjectMapper objectMapper,
                               @Value("${bakery.order.delivery-charge:65}") BigDecimal standardDeliveryCharge)
            throws IOException {
        this.standardDeliveryCharge = standardDeliveryCharge;
        var resource = new ClassPathResource("subscriptions.json");
        Root root = objectMapper.readValue(resource.getInputStream(), Root.class);
        this.itemTiers = root.itemTiers != null ? root.itemTiers : Map.of();
        this.packSizes = root.packSizes != null ? root.packSizes : Map.of();
        if (root.plans != null) {
            for (PlanConfig plan : root.plans) {
                byCode.put(plan.code, plan);
            }
        }
        log.info("Loaded {} subscription plan(s) from subscriptions.json", byCode.size());
    }

    /** Delivery charge for a plan: its own if set, otherwise the standard à la carte charge. */
    public BigDecimal effectiveDeliveryCharge(PlanConfig plan) {
        return plan.getDeliveryCharge() != null ? plan.getDeliveryCharge() : standardDeliveryCharge;
    }

    /** Individual pieces per pack for a menu category (Buns & Rolls = 6, Sweet Bakes = 4); loaves default to 1. */
    public int packSize(String category) {
        Integer n = category != null ? packSizes.get(category) : null;
        return n != null && n > 0 ? n : 1;
    }

    /** Total weekly deliveries = paid weeks + free bonus weeks. */
    public int totalWeeks(PlanConfig plan) {
        return plan.getCommitmentWeeks() + Math.max(0, plan.getBonusWeeks());
    }

    /** Prepaid upfront = (weekly price × paid weeks) + (delivery × every delivery week). Bonus weeks: free bread, paid delivery. */
    public BigDecimal totalUpfront(PlanConfig plan) {
        BigDecimal bakes = plan.getWeeklyPrice().multiply(BigDecimal.valueOf(plan.getCommitmentWeeks()));
        BigDecimal delivery = effectiveDeliveryCharge(plan).multiply(BigDecimal.valueOf(totalWeeks(plan)));
        return bakes.add(delivery);
    }

    /** Item names a customer may choose for a component, honouring an optional per-component tier override. */
    public List<String> chooseFrom(ComponentConfig component, String planTier) {
        String tier = component.getTier() != null ? component.getTier() : planTier;
        return chooseFrom(component.getType(), tier);
    }

    /** Active plans visible to the given audience ("FF" or "PUBLIC"), in file order. */
    public List<PlanConfig> activePlansForAudience(String audience) {
        return byCode.values().stream()
                .filter(p -> p.active)
                .filter(p -> audience != null && audience.equalsIgnoreCase(p.audience))
                .collect(Collectors.toList());
    }

    public Optional<PlanConfig> plan(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /**
     * Item names a customer may choose for a component of the given type under a plan of the given
     * tier: the union of that type's items at every tier up to and including the plan tier (so a
     * Premium plan offers Normal + Premium items).
     */
    public List<String> chooseFrom(String componentType, String planTier) {
        Map<String, List<String>> byTier = itemTiers.getOrDefault(componentType, Map.of());
        int maxIdx = TIER_ORDER.indexOf(planTier);
        List<String> out = new ArrayList<>();
        for (int i = 0; i <= maxIdx; i++) {
            List<String> items = byTier.get(TIER_ORDER.get(i));
            if (items != null) out.addAll(items);
        }
        return out;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Root {
        private Map<String, Map<String, List<String>>> itemTiers;
        private Map<String, Integer> packSizes;
        private List<PlanConfig> plans;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlanConfig {
        private String code;
        private String name;
        private String tier;       // NORMAL | PREMIUM
        private String audience;   // FF | PUBLIC
        private BigDecimal weeklyPrice;
        private BigDecimal deliveryCharge;
        private int commitmentWeeks;
        private int bonusWeeks;
        private boolean active;
        private List<OptionConfig> options;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OptionConfig {
        private String label;
        private String description;   // optional list-row subtitle (title has a 24-char WhatsApp limit)
        private List<ComponentConfig> components;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ComponentConfig {
        private String type;       // LOAF | ROLL | SWEET
        private String portion;    // FULL | HALF
        private int qty;
        private String tier;       // optional per-component tier override (else the plan tier)
    }
}

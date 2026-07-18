package com.tranche.bakery.offer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.order.OrderItemRepository;

import lombok.RequiredArgsConstructor;

/**
 * Dynamic batch discount: when an item already has enough demand booked for a
 * delivery day, the next customer gets an extra percentage off that item, to nudge
 * them into joining an existing bake batch. Bands (price range, unit threshold,
 * percent) are config-driven and tunable data-only. The master kill switch is
 * {@code bakery.batch-discount.enabled}.
 */
@Service
@RequiredArgsConstructor
public class BatchDiscountService {

    private final BatchDiscountBandRepository bandRepository;
    private final OrderItemRepository orderItemRepository;
    private final MenuItemRepository menuItemRepository;

    @Value("${bakery.batch-discount.enabled:true}")
    private boolean enabled;

    /** A live batch-discount offer to surface to a customer at greeting time. */
    public record Nudge(String itemName, BigDecimal percent, List<LocalDate> dates) {}

    public boolean isEnabled() {
        return enabled;
    }

    /** First active band whose price range contains the item list price, or null. */
    public BatchDiscountBand bandFor(BigDecimal price) {
        if (!enabled || price == null) return null;
        for (BatchDiscountBand b : bandRepository.findAllByActiveTrueOrderByDisplayOrderAsc()) {
            if (b.matchesPrice(price)) return b;
        }
        return null;
    }

    /**
     * Extra percent (e.g. 5.00) an item earns for a delivery date because its booked
     * demand has reached the band threshold, or null if it does not qualify.
     */
    public BigDecimal extraPercentFor(Long itemId, BigDecimal price, LocalDate date) {
        if (!enabled || itemId == null || date == null) return null;
        BatchDiscountBand band = bandFor(price);
        if (band == null) return null;
        long booked = orderItemRepository.sumBookedQuantityForItem(
                itemId, date, DeliveryRules.capacityStatuses());
        return booked >= band.getThresholdUnits() ? band.getPercent() : null;
    }

    /**
     * Items that are currently "hot" (batch discount live) across the given delivery
     * dates, for the greeting nudge. One entry per item, listing the dates it applies.
     */
    public List<Nudge> liveNudges(List<LocalDate> dates) {
        if (!enabled || dates == null || dates.isEmpty()) return List.of();

        List<Object[]> rows = orderItemRepository.sumBookedByItemAndDate(
                dates, DeliveryRules.capacityStatuses());
        if (rows.isEmpty()) return List.of();

        List<Long> itemIds = rows.stream().map(r -> (Long) r[0]).distinct().toList();
        Map<Long, MenuItem> items = new LinkedHashMap<>();
        for (MenuItem mi : menuItemRepository.findAllById(itemIds)) items.put(mi.getId(), mi);

        // itemId -> (name, percent, sorted dates)
        Map<Long, Nudge> byItem = new LinkedHashMap<>();
        Map<Long, List<LocalDate>> hotDates = new LinkedHashMap<>();
        for (Object[] r : rows) {
            Long itemId = (Long) r[0];
            LocalDate date = (LocalDate) r[1];
            long qty = ((Number) r[2]).longValue();
            MenuItem mi = items.get(itemId);
            if (mi == null || !mi.isActive()) continue;
            BatchDiscountBand band = bandFor(mi.getPrice());
            if (band == null || qty < band.getThresholdUnits()) continue;
            hotDates.computeIfAbsent(itemId, k -> new ArrayList<>()).add(date);
            byItem.put(itemId, new Nudge(mi.getName(), band.getPercent(), null));
        }

        List<Nudge> result = new ArrayList<>();
        for (Map.Entry<Long, Nudge> e : byItem.entrySet()) {
            List<LocalDate> ds = hotDates.get(e.getKey());
            ds.sort(Comparator.naturalOrder());
            result.add(new Nudge(e.getValue().itemName(), e.getValue().percent(), ds));
        }
        result.sort(Comparator.comparing((Nudge n) -> n.dates().get(0))
                .thenComparing(Nudge::itemName));
        return result;
    }
}

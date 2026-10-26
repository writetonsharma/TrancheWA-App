package com.tranche.bakery.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.offer.BatchDiscountBand;
import com.tranche.bakery.offer.BatchDiscountService;
import com.tranche.bakery.offer.PromoContext;
import com.tranche.bakery.offer.PromoResult;
import com.tranche.bakery.offer.PromotionEngine;

import lombok.RequiredArgsConstructor;

/** Read-only pricing shared by persisted orders and unreserved website previews. */
@Service
@RequiredArgsConstructor
public class CartPricingService {
    private final PromotionEngine promotionEngine;
    private final BatchDiscountService batchDiscountService;
    private final OrderRepository orderRepository;

    @Value("${bakery.order.delivery-charge:65}")
    private BigDecimal deliveryCharge;

    /** Subtotal is the caller's list-price snapshot, NOT a recalculation at today's menu price. */
    public record Line(MenuItem item, int quantity, BigDecimal subtotal) {}

    public record Price(BigDecimal listSubtotal, BigDecimal discountAmount, String discountLabel,
                        BigDecimal batchDiscountAmount, String batchDiscountLabel,
                        BigDecimal deliveryCharge, String giftLabel, BigDecimal creditApplied,
                        BigDecimal totalAmount) {
        /** Website empty baskets incur no fee/perks; legacy empty-order behaviour is unchanged. */
        public static Price empty() {
            return new Price(BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, null,
                    BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    /**
     * No persistence, reservation, credit consumption or payment calls. An order ID excludes
     * its existing demand from batch calculations; a website preview passes null.
     */
    public Price price(List<Line> lines, Customer customer, LocalDate date, Long excludeOrderId) {
        BigDecimal listSubtotal = lines.stream().map(Line::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overrideTotal = null;
        boolean freeDeliveryFlag = false;
        int completedOrders = 0;
        if (customer != null) {
            if (customer.hasActiveOverride()) {
                overrideTotal = BigDecimal.ZERO;
                for (Line line : lines) {
                    MenuItem item = line.item();
                    String name = item != null ? item.getName() : null;
                    String category = item != null && item.getCategory() != null
                            ? item.getCategory().getName() : null;
                    BigDecimal unit = customer.unitPriceFor(name, category);
                    overrideTotal = overrideTotal.add(unit != null
                            ? unit.multiply(BigDecimal.valueOf(line.quantity())) : line.subtotal());
                }
            }
            freeDeliveryFlag = customer.isFreeDelivery();
            completedOrders = (int) orderRepository.countByCustomerIdAndStatus(
                    customer.getId(), OrderStatus.COMPLETED);
        }

        PromoResult promo = promotionEngine.evaluate(
                new PromoContext(listSubtotal, overrideTotal, freeDeliveryFlag, completedOrders));
        BigDecimal fee = promo.freeDelivery() ? BigDecimal.ZERO : deliveryCharge;
        BigDecimal itemsTotal = promo.itemsTotal();
        BigDecimal batchDiscount = BigDecimal.ZERO;
        String batchLabel = null;
        boolean overrideActive = customer != null && customer.hasActiveOverride();
        if (date != null && !overrideActive && listSubtotal.signum() > 0) {
            BigDecimal discountedRatio = itemsTotal.divide(listSubtotal, 10, RoundingMode.HALF_UP);
            for (Line line : lines) {
                int qty = line.quantity();
                if (qty <= 0) continue;
                // Band lookup intentionally uses the current menu price; the monetary basis
                // remains the stored line subtotal, exactly as in OrderService before extraction.
                BigDecimal price = line.item().getPrice();
                BatchDiscountBand band = batchDiscountService.bandFor(price);
                if (band == null) continue;
                long units = batchDiscountService.discountableUnits(
                        line.item().getId(), price, date, qty, excludeOrderId);
                if (units <= 0) continue;
                BigDecimal discountedPerUnit = line.subtotal().multiply(discountedRatio)
                        .divide(BigDecimal.valueOf(qty), 10, RoundingMode.HALF_UP);
                batchDiscount = batchDiscount.add(discountedPerUnit
                        .multiply(BigDecimal.valueOf(units)).multiply(band.getPercent())
                        .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
                batchLabel = "Batch discount";
            }
            batchDiscount = batchDiscount.setScale(0, RoundingMode.HALF_UP);
        }
        if (batchDiscount.signum() > 0) {
            itemsTotal = itemsTotal.subtract(batchDiscount);
            if (itemsTotal.signum() < 0) itemsTotal = BigDecimal.ZERO;
        } else {
            batchDiscount = BigDecimal.ZERO;
            batchLabel = null;
        }

        BigDecimal gross = itemsTotal.add(fee);
        BigDecimal credit = BigDecimal.ZERO;
        if (customer != null && customer.getCreditBalance() != null
                && customer.getCreditBalance().signum() > 0 && gross.signum() > 0) {
            credit = customer.getCreditBalance().min(gross);
        }
        return new Price(listSubtotal, promo.discountAmount(), promo.discountLabel(), batchDiscount,
                batchLabel, fee, promo.giftLabel(), credit, gross.subtract(credit));
    }
}
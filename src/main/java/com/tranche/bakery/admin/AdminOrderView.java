package com.tranche.bakery.admin;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.payment.Payment;

import java.util.List;
import java.util.stream.Collectors;

public record AdminOrderView(
        Order order,
        List<OrderItem> items,
        Payment payment,
        String mapsUrl,
        String itemsSummary,
        boolean subscription
) {
    public static AdminOrderView of(Order order, List<OrderItem> items, Payment payment) {
        String mapsUrl = null;
        // Prefer order-level location, fall back to customer default
        var lat = order.getLocationLat() != null ? order.getLocationLat() : order.getCustomer().getLocationLat();
        var lng = order.getLocationLng() != null ? order.getLocationLng() : order.getCustomer().getLocationLng();
        if (lat != null && lng != null) {
            mapsUrl = "https://maps.google.com/?q=" + lat + "," + lng;
        }

        boolean subscription = order.getSubscriptionId() != null;
        String summary = (subscription ? "🗓️ [Subscription] " : "") + items.stream()
                .map(i -> i.getMenuItem().getName() + " × " + i.getQuantity()
                        + (i.getNote() != null ? " (" + i.getNote() + ")" : ""))
                .collect(Collectors.joining(", "));

        return new AdminOrderView(order, items, payment, mapsUrl, summary, subscription);
    }
}

package com.tranche.bakery.admin;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderChannel;
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
        // WEB checkout addresses are independent of the customer's WhatsApp default pin.
        boolean useCustomerLocation = order.getChannel() != OrderChannel.WEB;
        var lat = order.getLocationLat() != null ? order.getLocationLat()
            : useCustomerLocation ? order.getCustomer().getLocationLat() : null;
        var lng = order.getLocationLng() != null ? order.getLocationLng()
            : useCustomerLocation ? order.getCustomer().getLocationLng() : null;
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

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
        String itemsSummary
) {
    public static AdminOrderView of(Order order, List<OrderItem> items, Payment payment) {
        String mapsUrl = null;
        if (order.getCustomer().getLocationLat() != null && order.getCustomer().getLocationLng() != null) {
            mapsUrl = "https://maps.google.com/?q=" +
                    order.getCustomer().getLocationLat() + "," +
                    order.getCustomer().getLocationLng();
        }

        String summary = items.stream()
                .map(i -> i.getMenuItem().getName() + " × " + i.getQuantity())
                .collect(Collectors.joining(", "));

        return new AdminOrderView(order, items, payment, mapsUrl, summary);
    }
}

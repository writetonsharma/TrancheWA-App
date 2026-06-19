package com.tranche.bakery.admin;

public record BakeListItem(
        String itemName,
        int totalQuantity,
        int orderCount
) {}

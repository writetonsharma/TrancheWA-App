package com.tranche.bakery.admin;

import java.time.LocalDate;
import java.util.List;

public record BakeSheet(
        LocalDate date,
        List<BakeListItem> aggregate,
        List<AdminOrderView> orders,
        int totalItems
) {}
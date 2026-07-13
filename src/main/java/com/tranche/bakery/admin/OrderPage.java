package com.tranche.bakery.admin;

import java.util.List;

/**
 * A single page of filtered order results for the admin Orders page,
 * carrying the rows plus paging metadata for the template.
 */
public record OrderPage(
        List<AdminOrderView> orders,
        int page,
        int totalPages,
        long totalElements,
        int size
) {
    public boolean hasPrevious() { return page > 0; }
    public boolean hasNext() { return page < totalPages - 1; }
    public int displayPage() { return page + 1; }
}

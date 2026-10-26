package com.tranche.bakery.web.shop;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Explicit JSON contract (no JPA entities or private customer/capacity fields).
 * GET /api/catalog -> {items:[{id,name,category,description,weightLabel,price}],
 * offers:[{label,kind,percent,thresholdAmount,giftLabel}],deliveryDates:["yyyy-MM-dd"]}.
 * Prices here and on quote lines are list prices; basket-wide discounts are separate.
 * Offers describe live public promotions, not a guarantee of qualification or stacking.
 * Delivery dates are indicative public dates, not availability for a particular basket.
 *
 * POST /api/quote accepts ONLY {lines:[{itemId:integer,quantity:integer}],date?:"yyyy-MM-dd"}.
 * At most 40 input lines, each quantity 1..99; duplicate IDs merge up to 99 per item.
 * Response -> {lines:[{itemId,name,quantity,unitPrice,lineTotal}],listSubtotal,
 * discountAmount,discountLabel,batchDiscountAmount,batchDiscountLabel,deliveryCharge,
 * giftLabel,creditApplied,totalAmount,itemCount,date,valid,problems:[{code,message}]}.
 * Optional labels/date serialize as null; amounts are JSON numbers in INR.
 * valid means this preview passed current checks, NEVER an order, checkout or reservation.
 * Empty baskets return zero amounts and EMPTY_CART; no date returns DATE_REQUIRED.
 * Capacity failure returns FULLY_BOOKED without disclosing any booked/remaining counts.
 *
 * HTTP 400 invalid input/day/lead-time -> {valid:false,problems:[{code,message}]}.
 * The same error shape applies to 404 DISABLED and 401 INVALID_SESSION.
 * No input, IDs, phone numbers, SQL errors or exception details are echoed in errors.
 * All catalogue/quote responses (including errors) are private, no-store.
 */
public final class StorefrontDtos {
    private StorefrontDtos() {}

    public record Catalog(List<CatalogItem> items, List<PublishedOffer> offers,
                          List<LocalDate> deliveryDates) {}
    public record CatalogItem(Long id, String name, String category, String description,
                              String weightLabel, BigDecimal price) {}
    public record PublishedOffer(String label, String kind, BigDecimal percent,
                                 BigDecimal thresholdAmount, String giftLabel) {}
    public record QuoteLine(Long itemId, String name, int quantity,
                            BigDecimal unitPrice, BigDecimal lineTotal) {}
    public record Problem(String code, String message) {}
    public record Error(boolean valid, List<Problem> problems) {}
    public record Quote(List<QuoteLine> lines, BigDecimal listSubtotal,
                        BigDecimal discountAmount, String discountLabel,
                        BigDecimal batchDiscountAmount, String batchDiscountLabel,
                        BigDecimal deliveryCharge, String giftLabel, BigDecimal creditApplied,
                        BigDecimal totalAmount, int itemCount, LocalDate date,
                        boolean valid, List<Problem> problems) {}
}
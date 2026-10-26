package com.tranche.bakery.web.shop;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.menu.MenuCategory;
import com.tranche.bakery.menu.MenuCategoryRepository;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.offer.OfferRepository;
import com.tranche.bakery.order.CartPricingService;
import com.tranche.bakery.order.DeliveryRules;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorefrontCatalogService {
    private static final int MAX_LINES = 40;
    private static final int MAX_QUANTITY = 99;
    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;
    private final OfferRepository offerRepository;
    private final CartPricingService pricingService;
    private final DeliveryRules deliveryRules;

    public StorefrontDtos.Catalog catalog() {
        List<StorefrontDtos.CatalogItem> items = new ArrayList<>();
        for (MenuCategory category : categoryRepository.findAllByActiveTrueOrderByDisplayOrderAsc()) {
            if (!category.isActive()) continue;
            for (MenuItem item : itemRepository.findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(category)) {
                if (!isActive(item)) continue;
                items.add(new StorefrontDtos.CatalogItem(item.getId(), item.getName(), category.getName(),
                        item.getDescription(), item.getWeightLabel(), item.getPrice()));
            }
        }
        LocalDateTime now = LocalDateTime.now(); // Application default timezone is Asia/Kolkata.
        List<StorefrontDtos.PublishedOffer> offers = offerRepository.findAllByOrderByDisplayOrderAsc()
                .stream().filter(offer -> offer.isLiveAt(now))
                .map(offer -> new StorefrontDtos.PublishedOffer(offer.getLabel(), offer.getKind().name(),
                        offer.getPercent(), offer.getThresholdAmount(), offer.getGiftLabel())).toList();
        DeliveryRules.CartFlags flags = new DeliveryRules.CartFlags(false, false, 0, false);
        List<LocalDate> dates = deliveryRules.upcomingDeliverableDays(7).stream()
                .filter(date -> deliveryRules.hasCapacity(date, flags)).toList();
        return new StorefrontDtos.Catalog(List.copyOf(items), offers, dates);
    }

    public StorefrontDtos.Quote quote(JsonNode body, Customer customer) {
        requireFields(body, Set.of("lines", "date"));
        JsonNode requestedLines = body.get("lines");
        if (requestedLines == null || !requestedLines.isArray()) {
            throw bad("INVALID_REQUEST", "Supply a lines array.");
        }
        if (requestedLines.size() > MAX_LINES) {
            throw bad("TOO_MANY_LINES", "A basket may contain at most 40 lines.");
        }
        LocalDate date = parseDate(body.get("date"));
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (JsonNode line : requestedLines) {
            requireFields(line, Set.of("itemId", "quantity"));
            JsonNode id = line.get("itemId");
            JsonNode qty = line.get("quantity");
            // JsonNode deliberately prevents Jackson coercing fractions, strings or booleans to integers.
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
                throw bad("INVALID_ITEM_ID", "Every line needs a positive integer itemId.");
            }
            if (qty == null || !qty.isIntegralNumber() || !qty.canConvertToInt()
                    || qty.intValue() < 1 || qty.intValue() > MAX_QUANTITY) {
                throw bad("INVALID_QUANTITY", "Each quantity must be an integer from 1 to 99.");
            }
            // Bounded operands plus long arithmetic make duplicate merging overflow-safe.
            long merged = (long) quantities.getOrDefault(id.longValue(), 0) + qty.intValue();
            if (merged > MAX_QUANTITY) {
                throw bad("INVALID_QUANTITY", "The combined quantity for an item cannot exceed 99.");
            }
            quantities.put(id.longValue(), (int) merged);
        }

        Map<Long, MenuItem> menu = itemRepository.findAllById(quantities.keySet()).stream()
                .filter(StorefrontCatalogService::isActive)
                .collect(Collectors.toMap(MenuItem::getId, Function.identity()));
        if (!menu.keySet().containsAll(quantities.keySet())) {
            throw bad("ITEM_UNAVAILABLE", "One or more items are unavailable. Please refresh the catalogue.");
        }
        boolean friendsAndFamily = customer != null && customer.isFriendsAndFamily();
        if (date != null) {
            // Also validates an empty basket's date against Wednesday closure and normal lead time.
            DeliveryRules.CartFlags emptyFlags = new DeliveryRules.CartFlags(false, false, 0, friendsAndFamily);
            if (date.isBefore(deliveryRules.earliestDate(emptyFlags))
                    || !deliveryRules.isDeliverableDay(date, emptyFlags)
                    || menu.values().stream().anyMatch(item ->
                        !deliveryRules.itemDeliverableOn(item.getName(), date, friendsAndFamily))) {
                throw bad("INVALID_DATE", "Choose a later delivery date available for all selected items.");
            }
        }

        List<StorefrontDtos.QuoteLine> lines = new ArrayList<>();
        List<CartPricingService.Line> pricingLines = new ArrayList<>();
        int itemCount = 0; // At most 40 * 99, after input validation.
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            MenuItem item = menu.get(entry.getKey());
            int quantity = entry.getValue();
            BigDecimal subtotal = item.getPrice().multiply(BigDecimal.valueOf(quantity));
            pricingLines.add(new CartPricingService.Line(item, quantity, subtotal));
            lines.add(new StorefrontDtos.QuoteLine(item.getId(), item.getName(), quantity,
                    item.getPrice(), subtotal));
            itemCount += quantity;
        }
        List<StorefrontDtos.Problem> problems = new ArrayList<>();
        if (lines.isEmpty()) {
            problems.add(new StorefrontDtos.Problem("EMPTY_CART", "Add an item to preview an order."));
        } else if (date == null) {
            problems.add(new StorefrontDtos.Problem("DATE_REQUIRED", "Choose a delivery date."));
        } else if (!deliveryRules.hasCapacity(date,
                new DeliveryRules.CartFlags(false, false, itemCount, friendsAndFamily))) {
            problems.add(new StorefrontDtos.Problem("FULLY_BOOKED", "This delivery date is fully booked."));
        }
        CartPricingService.Price price = lines.isEmpty() ? CartPricingService.Price.empty()
                : pricingService.price(pricingLines, customer, date, null);
        return new StorefrontDtos.Quote(List.copyOf(lines), price.listSubtotal(), price.discountAmount(),
                price.discountLabel(), price.batchDiscountAmount(), price.batchDiscountLabel(),
                price.deliveryCharge(), price.giftLabel(), price.creditApplied(), price.totalAmount(),
                itemCount, date, problems.isEmpty(), List.copyOf(problems));
    }

    private static boolean isActive(MenuItem item) {
        return item.isActive() && item.getCategory() != null && item.getCategory().isActive();
    }

    private static void requireFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw bad("INVALID_REQUEST", "Supply a valid quote request.");
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) throw bad("INVALID_REQUEST", "The request contains unsupported fields.");
        }
    }

    private static LocalDate parseDate(JsonNode date) {
        if (date == null || date.isNull()) return null;
        if (!date.isTextual() || !date.textValue().matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw bad("INVALID_DATE", "Supply a delivery date in yyyy-MM-dd format.");
        }
        try {
            return LocalDate.parse(date.textValue());
        } catch (DateTimeParseException exception) {
            throw bad("INVALID_DATE", "Supply a valid delivery date in yyyy-MM-dd format.");
        }
    }

    private static StorefrontProblemException bad(String code, String message) {
        return new StorefrontProblemException(HttpStatus.BAD_REQUEST, code, message);
    }
}
package com.tranche.bakery.web.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.menu.MenuCategory;
import com.tranche.bakery.menu.MenuCategoryRepository;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.offer.BatchDiscountBand;
import com.tranche.bakery.offer.BatchDiscountService;
import com.tranche.bakery.offer.Offer;
import com.tranche.bakery.offer.OfferGroup;
import com.tranche.bakery.offer.OfferKind;
import com.tranche.bakery.offer.OfferRepository;
import com.tranche.bakery.offer.PromotionEngine;
import com.tranche.bakery.order.CartPricingService;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderNumberGenerator;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.RazorpayService;

/** Pure unit/standalone-MVC tests: no shared FlowScenarioBase, database, OTP or gateway. */
class StorefrontQuoteTest {
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private MenuCategoryRepository categories;
    private MenuItemRepository items;
    private OfferRepository offers;
    private OrderRepository orders;
    private OrderItemRepository orderItems;
    private BatchDiscountService batches;
    private PaymentRepository payments;
    private RazorpayService gateway;
    private CartPricingService pricing;
    private DeliveryRules rules;
    private StorefrontCatalogService service;
    private StorefrontController controller;
    private MockMvc mvc;
    private MenuCategory category;
    private MenuItem bread;
    private final Map<Long, MenuItem> menu = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        categories = mock(MenuCategoryRepository.class);
        items = mock(MenuItemRepository.class);
        offers = mock(OfferRepository.class);
        orders = mock(OrderRepository.class);
        orderItems = mock(OrderItemRepository.class);
        batches = mock(BatchDiscountService.class);
        payments = mock(PaymentRepository.class);
        gateway = mock(RazorpayService.class);
        pricing = new CartPricingService(new PromotionEngine(offers), batches, orders);
        ReflectionTestUtils.setField(pricing, "deliveryCharge", new BigDecimal("65"));
        rules = new DeliveryRules(orderItems);
        ReflectionTestUtils.setField(rules, "cutoffHour", 17);
        ReflectionTestUtils.setField(rules, "dailyCapacity", 15);
        service = new StorefrontCatalogService(categories, items, offers, pricing, rules);
        controller = new StorefrontController(service, new StorefrontCustomerContext());
        ReflectionTestUtils.setField(controller, "enabled", true);
        mvc = mvcFor(controller);
        category = new MenuCategory();
        category.setId(1L);
        category.setName("Breads");
        menu.clear();
        bread = addItem(1L, "Country loaf", "100");
        when(offers.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(categories.findAllByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(category));
        when(items.findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(category))
                .thenAnswer(invocation -> List.copyOf(menu.values()));
        when(items.findAllById(any())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            List<MenuItem> found = new ArrayList<>();
            ids.forEach(id -> { if (menu.containsKey(id)) found.add(menu.get(id)); });
            return found;
        });
    }

    @ParameterizedTest
    @CsvSource({"2,225,40,65", "6,545,120,65", "7,560,140,0"})
    void quoteAndOrderShareStandardPromotionPipeline(int quantity, String total, String discount, String fee)
            throws Exception {
        launchOffers();
        StorefrontDtos.Quote quote = quote(quantity, null, future(DayOfWeek.FRIDAY));
        assertMoney(quote.totalAmount(), total);
        assertMoney(quote.discountAmount(), discount);
        assertMoney(quote.deliveryCharge(), fee);
        assertThat(quote.giftLabel()).isEqualTo(quantity >= 6 ? "Complimentary roll" : null);
        assertThat(quote.valid()).isTrue();
        assertMatchesOrder(quote, null, quantity);
    }

    @Test
    void friendsFamilyItemOverridesBeatCategoryAndGlobalWithoutGiftOrBatch() throws Exception {
        launchOffers();
        Customer customer = customer();
        customer.setPricingOverride(new BigDecimal("70"));
        customer.getCategoryPrices().put("Breads", new BigDecimal("40"));
        customer.getItemPrices().put(bread.getName(), new BigDecimal("20"));
        StorefrontDtos.Quote quote = quote(7, customer, future(DayOfWeek.FRIDAY));
        assertMoney(quote.listSubtotal(), "700");
        assertMoney(quote.totalAmount(), "205");
        assertMoney(quote.discountAmount(), "560");
        assertThat(quote.discountLabel()).isEqualTo("Special rate");
        assertThat(quote.giftLabel()).isNull();
        assertMoney(quote.batchDiscountAmount(), "0");
        verifyNoInteractions(batches);
        assertMatchesOrder(quote, customer, 7);
    }

    @Test
    void betterPublicOfferWinsButAnyLiveOverrideStillSuppressesGiftAndBatch() throws Exception {
        launchOffers();
        Customer customer = customer();
        customer.setPricingOverride(new BigDecimal("95"));
        StorefrontDtos.Quote quote = quote(7, customer, future(DayOfWeek.FRIDAY));
        assertMoney(quote.totalAmount(), "560");
        assertThat(quote.discountLabel()).isEqualTo("Launch offer");
        assertThat(quote.giftLabel()).isNull();
        verifyNoInteractions(batches);
        assertMatchesOrder(quote, customer, 7);
    }

    @Test
    void expiredOverrideDoesNotSuppressPublicGift() throws Exception {
        launchOffers();
        Customer customer = customer();
        customer.setPricingOverride(BigDecimal.ONE);
        customer.setOverrideExpiresAt(LocalDateTime.now().minusDays(1));
        StorefrontDtos.Quote quote = quote(7, customer, future(DayOfWeek.FRIDAY));
        assertMoney(quote.totalAmount(), "560");
        assertThat(quote.giftLabel()).isEqualTo("Complimentary roll");
        assertMatchesOrder(quote, customer, 7);
    }

    @ParameterizedTest
    @CsvSource({"25,175,25", "9999,0,200"})
    void authenticatedFreeDeliveryAndCreditAreClampedAndNotConsumed(String credit, String total, String applied)
            throws Exception {
        Customer customer = customer();
        customer.setFreeDelivery(true);
        customer.setCreditBalance(new BigDecimal(credit));
        StorefrontDtos.Quote quote = quote(2, customer, future(DayOfWeek.FRIDAY));
        assertMoney(quote.deliveryCharge(), "0");
        assertMoney(quote.creditApplied(), applied);
        assertMoney(quote.totalAmount(), total);
        assertMoney(customer.getCreditBalance(), credit);
        assertMatchesOrder(quote, customer, 2);
    }

    @Test
    void batchStacksOnAlreadyDiscountedUnitsAndKeepsDeliveryGiftThresholdsBeforeBatch() throws Exception {
        launchOffers();
        LocalDate date = future(DayOfWeek.FRIDAY);
        BatchDiscountBand band = new BatchDiscountBand();
        band.setPercent(new BigDecimal("10"));
        when(batches.bandFor(bread.getPrice())).thenReturn(band);
        when(batches.discountableUnits(bread.getId(), bread.getPrice(), date, 7, null)).thenReturn(2L);
        when(batches.discountableUnits(bread.getId(), bread.getPrice(), date, 7, 77L)).thenReturn(2L);
        StorefrontDtos.Quote quote = quote(7, null, date);
        assertMoney(quote.batchDiscountAmount(), "16");
        assertMoney(quote.totalAmount(), "544");
        assertMoney(quote.deliveryCharge(), "0");
        assertThat(quote.giftLabel()).isEqualTo("Complimentary roll");
        assertMatchesOrder(quote, null, 7);
        verify(batches).discountableUnits(1L, bread.getPrice(), date, 7, 77L);
    }

    @Test
    void fractionalMoneyKeepsWholeRupeePromoAndBatchRoundingAndAppliesCreditLast() throws Exception {
        launchOffers();
        bread.setPrice(new BigDecimal("100.05"));
        LocalDate date = future(DayOfWeek.FRIDAY);
        BatchDiscountBand band = new BatchDiscountBand();
        band.setPercent(new BigDecimal("10"));
        when(batches.bandFor(bread.getPrice())).thenReturn(band);
        when(batches.discountableUnits(1L, bread.getPrice(), date, 3, null)).thenReturn(1L);
        when(batches.discountableUnits(1L, bread.getPrice(), date, 3, 77L)).thenReturn(1L);
        Customer customer = customer();
        customer.setCreditBalance(BigDecimal.TEN);
        StorefrontDtos.Quote quote = quote(3, customer, date);
        assertMoney(quote.listSubtotal(), "300.15");
        assertMoney(quote.discountAmount(), "60");
        assertMoney(quote.batchDiscountAmount(), "8");
        assertMoney(quote.creditApplied(), "10");
        assertMoney(quote.totalAmount(), "287.15");
        assertMatchesOrder(quote, customer, 3);
    }

    @Test
    void equalPublicDiscountsUsePriorityRatherThanDisplayOrder() throws Exception {
        Offer first = offer(OfferKind.PERCENT_OFF, OfferGroup.DISCOUNT, "Lower priority", "20", null);
        Offer winner = offer(OfferKind.PERCENT_OFF, OfferGroup.DISCOUNT, "Higher priority", "20", null);
        winner.setPriority(10);
        when(offers.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of(first, winner));
        StorefrontDtos.Quote quote = quote(2, null, future(DayOfWeek.FRIDAY));
        assertMoney(quote.totalAmount(), "225");
        assertThat(quote.discountLabel()).isEqualTo("Higher priority");
        assertMatchesOrder(quote, null, 2);
    }

    @Test
    void persistedOrderRetainsHistoricalSubtotalEvenWhenMenuPriceChanges() {
        bread.setPrice(new BigDecimal("900"));
        Order order = new Order();
        order.setId(77L);
        OrderItem line = orderLine(order, 2, new BigDecimal("100"));
        when(orderItems.findAllByOrderId(77L)).thenReturn(List.of(line));
        orderService().recalculate(order);
        assertMoney(order.getTotalAmount(), "265");
        assertMoney(line.getUnitPrice(), "100");
        assertMoney(line.getSubtotal(), "200");
    }

    @Test
    void emptyWebBasketIsZeroWithoutChangingLegacyEmptyOrderFee() throws Exception {
        StorefrontDtos.Quote quote = service.quote(mapper.readTree("{\"lines\":[]}"), customer());
        assertMoney(quote.totalAmount(), "0");
        assertMoney(quote.deliveryCharge(), "0");
        assertMoney(quote.creditApplied(), "0");
        assertThat(quote.itemCount()).isZero();
        assertThat(quote.valid()).isFalse();
        assertThat(quote.problems()).extracting(StorefrontDtos.Problem::code).containsExactly("EMPTY_CART");
        verifyNoInteractions(offers, orders, batches, payments, gateway);

        Order order = new Order();
        order.setId(77L);
        when(orderItems.findAllByOrderId(77L)).thenReturn(List.of());
        orderService().recalculate(order);
        assertMoney(order.getDeliveryCharge(), "65");
        assertMoney(order.getTotalAmount(), "65");
    }

    @Test
    void duplicateLinesMergeOnceAndDoNotChangePricingOrCount() throws Exception {
        StorefrontDtos.Quote quote = service.quote(mapper.readTree("""
                {"lines":[{"itemId":1,"quantity":2},{"itemId":1,"quantity":3}]}
                """), null);
        assertThat(quote.lines()).hasSize(1);
        assertThat(quote.lines().get(0).quantity()).isEqualTo(5);
        assertThat(quote.itemCount()).isEqualTo(5);
        assertMoney(quote.totalAmount(), "565");
        assertThat(quote.valid()).isFalse();
        assertThat(quote.problems()).extracting(StorefrontDtos.Problem::code).containsExactly("DATE_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "100", "2147483647", "2147483648", "9999999999999999999999",
            "1.5", "1.0", "\"2\"", "true", "null"})
    void invalidQuantitiesAre400WithoutCoercionOrOverflow(String quantity) throws Exception {
        invalid("{\"lines\":[{\"itemId\":1,\"quantity\":" + quantity + "}]}", "INVALID_QUANTITY");
        verifyNoInteractions(items, offers, orders, batches);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "9223372036854775808", "1.5", "\"1\"", "null"})
    void invalidIdsAre400(String id) throws Exception {
        invalid("{\"lines\":[{\"itemId\":" + id + ",\"quantity\":1}]}", "INVALID_ITEM_ID");
    }

    @Test
    void duplicateOverflowAndFortyOneLinesAreRejectedBeforeLookup() throws Exception {
        invalid("{\"lines\":[{\"itemId\":1,\"quantity\":99},{\"itemId\":1,\"quantity\":1}]}",
                "INVALID_QUANTITY");
        String line = "{\"itemId\":1,\"quantity\":1}";
        invalid("{\"lines\":[" + String.join(",", java.util.Collections.nCopies(41, line)) + "]}",
                "TOO_MANY_LINES");
        verifyNoInteractions(items, orders, offers, batches);
    }

    @Test
    void fortyInputLinesAreAllowedAndMerged() throws Exception {
        String line = "{\"itemId\":1,\"quantity\":1}";
        mvc.perform(post("/api/quote").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[" + String.join(",", java.util.Collections.nCopies(40, line)) + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(40))
                .andExpect(jsonPath("$.lines.length()").value(1));
    }

    @Test
    void ninetyNineIsAllowedButCapacityIsOnlyAGenericProblem() throws Exception {
        mvc.perform(post("/api/quote").contentType(MediaType.APPLICATION_JSON)
                .content(request(99, future(DayOfWeek.FRIDAY)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.problems[0].code").value("FULLY_BOOKED"))
                .andExpect(jsonPath("$.bookedQuantity").doesNotExist())
                .andExpect(jsonPath("$.remainingCapacity").doesNotExist())
                .andExpect(jsonPath("$.dailyCapacity").doesNotExist());
    }

    @Test
    void retiredMissingAndInactiveCategoryItemsAreIndistinguishablyUnavailable() throws Exception {
        bread.setActive(false);
        invalid(request(1, null), "ITEM_UNAVAILABLE");
        bread.setActive(true);
        category.setActive(false);
        invalid(request(1, null), "ITEM_UNAVAILABLE");
        category.setActive(true);
        invalid("{\"lines\":[{\"itemId\":999,\"quantity\":1}]}", "ITEM_UNAVAILABLE");
        verifyNoInteractions(offers, orders, batches, payments, gateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2026-02-30\"", "\"2026-13-01\"", "\"2026-1-01\"", "\"tomorrow\"",
            "1", "[]", "\"999999999-01-01\""})
    void malformedDatesReturnSanitized400(String date) throws Exception {
        invalid("{\"lines\":[],\"date\":" + date + "}", "INVALID_DATE");
    }

    @Test
    void pastWednesdayAndWeekendOnlyRestrictionsUseExistingRules() throws Exception {
        invalid(request(1, LocalDate.now().minusDays(1)), "INVALID_DATE");
        invalid(request(1, future(DayOfWeek.WEDNESDAY)), "INVALID_DATE");
        bread.setName("Rosemary focaccia");
        LocalDate monday = future(DayOfWeek.MONDAY);
        invalid(request(1, monday), "INVALID_DATE");
        Customer eligible = customer();
        eligible.setSubscriptionEligible(true); // Existing F&F helper, not only pricing overrides.
        assertThat(quote(1, eligible, monday).valid()).isTrue();
        assertThat(quote(1, null, future(DayOfWeek.FRIDAY)).valid()).isTrue();
    }

    @Test
    void bagelLeadTimeIsEnforcedAndLongerLeadIsAccepted() throws Exception {
        bread.setName("Sesame bagel");
        LocalDate tooEarly = rules.earliestDate(new DeliveryRules.CartFlags(false, false, 1, false));
        invalid(request(1, tooEarly), "INVALID_DATE");
        assertThat(quote(1, null, future(DayOfWeek.FRIDAY)).valid()).isTrue();
    }

    @Test
    void capacityDoesNotReserveOrRevealCounts() throws Exception {
        LocalDate date = future(DayOfWeek.FRIDAY);
        when(orderItems.sumBookedQuantity(date, DeliveryRules.capacityStatuses())).thenReturn(15L);
        mvc.perform(post("/api/quote").contentType(MediaType.APPLICATION_JSON).content(request(1, date)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.problems[0].message").value("This delivery date is fully booked."));
        verify(orders, never()).save(any());
        verify(orderItems, never()).save(any());
        verifyNoInteractions(payments, gateway);
    }

    @Test
    void explicitCatalogProjectionHasOnlyActiveItemsAndLivePublicOfferFields() throws Exception {
        addItem(2L, "Retired private product", "200").setActive(false);
        MenuItem hidden = addItem(3L, "Inactive category product", "200");
        MenuCategory inactive = new MenuCategory();
        inactive.setActive(false);
        hidden.setCategory(inactive);
        Offer live = offer(OfferKind.PERCENT_OFF, OfferGroup.DISCOUNT, "Public offer", "20", null);
        live.setCode("INTERNAL_CODE");
        live.setPriority(99);
        Offer expired = offer(OfferKind.PERCENT_OFF, OfferGroup.DISCOUNT, "Expired", "99", null);
        expired.setEndsAt(LocalDateTime.now().minusDays(1));
        Offer future = offer(OfferKind.PERCENT_OFF, OfferGroup.DISCOUNT, "Future", "99", null);
        future.setStartsAt(LocalDateTime.now().plusDays(1));
        Offer paused = offer(OfferKind.PERCENT_OFF, OfferGroup.DISCOUNT, "Paused", "99", null);
        paused.setActive(false);
        when(offers.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of(live, expired, future, paused));
        String json = mvc.perform(get("/api/catalog")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].price").value(100))
                .andExpect(jsonPath("$.offers.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode response = mapper.readTree(json);
        assertFields(response, Set.of("items", "offers", "deliveryDates"));
        assertFields(response.get("items").get(0),
                Set.of("id", "name", "category", "description", "weightLabel", "price"));
        assertFields(response.get("offers").get(0),
                Set.of("label", "kind", "percent", "thresholdAmount", "giftLabel"));
        assertThat(json).doesNotContain("Retired", "Inactive category", "INTERNAL_CODE", "priority",
                "customer", "capacity", "creditBalance");
    }

    @Test
    void quoteContractContainsOnlyExplicitFieldsAndNoCustomerEntity() throws Exception {
        Customer customer = customer();
        customer.setPricingOverride(BigDecimal.TEN);
        customer.setCreditBalance(BigDecimal.ONE);
        String json = mvc.perform(post("/api/quote").requestAttr(StorefrontCustomerContext.CUSTOMER_ATTRIBUTE, customer)
                .contentType(MediaType.APPLICATION_JSON).content(request(1, future(DayOfWeek.FRIDAY))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(jsonPath("$.totalAmount").value(74))
                .andReturn().getResponse().getContentAsString();
        JsonNode response = mapper.readTree(json);
        assertFields(response, Set.of("lines", "listSubtotal", "discountAmount", "discountLabel",
                "batchDiscountAmount", "batchDiscountLabel", "deliveryCharge", "giftLabel", "creditApplied",
                "totalAmount", "itemCount", "date", "valid", "problems"));
        assertFields(response.get("lines").get(0), Set.of("itemId", "name", "quantity", "unitPrice", "lineTotal"));
        assertThat(json).doesNotContain(customer.getPhone(), customer.getName(), "creditBalance", "customerId",
                "deliveryAddress", "marketingOptIn", "subscriptionEligible", "overrideNote", "capacity");
    }

    @Test
    void anonymousAndAdminPrincipalNeverAcquireCustomerPricesFromPhoneParameters() throws Exception {
        mvc.perform(post("/api/quote").principal(() -> "admin").param("phone", "+919999999999")
                .param("customerId", "42").contentType(MediaType.APPLICATION_JSON).content(request(1, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAmount").value(165))
                .andExpect(jsonPath("$.creditApplied").value(0));
        verifyNoInteractions(orders);
        invalid("{\"lines\":[],\"customerId\":42}", "INVALID_REQUEST");
        invalid("{\"lines\":[],\"phone\":\"+919999999999\"}", "INVALID_REQUEST");
        invalid("{\"lines\":[{\"itemId\":1,\"quantity\":1,\"price\":0}]}", "INVALID_REQUEST");
    }

    @Test
    void wrongOrUnsavedCustomerAttributeFailsClosed() throws Exception {
        mvc.perform(post("/api/quote").requestAttr(StorefrontCustomerContext.CUSTOMER_ATTRIBUTE, "admin")
                .contentType(MediaType.APPLICATION_JSON).content(request(1, null)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.problems[0].code").value("INVALID_SESSION"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(StorefrontCustomerContext.CUSTOMER_ATTRIBUTE, new Customer());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new StorefrontCustomerContext().currentCustomer(request))
                .isInstanceOf(StorefrontProblemException.class);
        verifyNoInteractions(items, offers, orders, batches);
    }

    @Test
    void disabledByDefaultAndCheckedOnEveryRequest() throws Exception {
        MockMvc disabled = mvcFor(new StorefrontController(service, new StorefrontCustomerContext()));
        disabled.perform(get("/api/catalog")).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(jsonPath("$.problems[0].code").value("DISABLED"));
        disabled.perform(post("/api/quote").contentType(MediaType.APPLICATION_JSON).content(request(1, null)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(categories, items, offers, orders, orderItems, batches);
        mvc.perform(get("/api/catalog")).andExpect(status().isOk());
        clearInvocations(categories, items, offers, orders, orderItems, batches);
        ReflectionTestUtils.setField(controller, "enabled", false);
        mvc.perform(post("/api/quote").contentType(MediaType.APPLICATION_JSON).content(request(1, null)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(categories, items, offers, orders, orderItems, batches);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{}", "{\"lines\":null}", "{\"lines\":[null]}",
            "{\"lines\":[{}]}", "{\"lines\":[{\"itemId\":1}]}"})
    void malformedRequestsAre400Not500(String body) throws Exception {
        mvc.perform(post("/api/quote").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.problems[0].code").isString())
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    private MockMvc mvcFor(StorefrontController target) {
        return MockMvcBuilders.standaloneSetup(target).setControllerAdvice(new StorefrontProblemAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private MenuItem addItem(long id, String name, String price) {
        MenuItem item = new MenuItem();
        item.setId(id);
        item.setCategory(category);
        item.setName(name);
        item.setPrice(new BigDecimal(price));
        menu.put(id, item);
        return item;
    }

    private Customer customer() {
        Customer customer = new Customer();
        customer.setId(42L);
        customer.setPhone("+919999999999");
        customer.setName("Private customer name");
        return customer;
    }

    private LocalDate future(DayOfWeek day) {
        return LocalDate.now().plusDays(14).with(TemporalAdjusters.nextOrSame(day));
    }

    private String request(int quantity, LocalDate date) {
        return "{\"lines\":[{\"itemId\":1,\"quantity\":" + quantity + "}]"
                + (date == null ? "" : ",\"date\":\"" + date + "\"") + "}";
    }

    private StorefrontDtos.Quote quote(int quantity, Customer customer, LocalDate date) throws Exception {
        return service.quote(mapper.readTree(request(quantity, date)), customer);
    }

    private void invalid(String json, String code) throws Exception {
        mvc.perform(post("/api/quote").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.problems[0].code").value(code))
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    private OrderService orderService() {
        return new OrderService(orders, orderItems, items, pricing, payments, gateway, mock(OrderNumberGenerator.class));
    }

    private OrderItem orderLine(Order order, int quantity, BigDecimal unit) {
        OrderItem line = new OrderItem();
        line.setOrder(order);
        line.setMenuItem(bread);
        line.setQuantity(quantity);
        line.setUnitPrice(unit);
        line.setSubtotal(unit.multiply(BigDecimal.valueOf(quantity)));
        return line;
    }

    private void assertMatchesOrder(StorefrontDtos.Quote quote, Customer customer, int quantity) {
        // Web quotation itself must never save or consume anything.
        verify(orders, never()).save(any());
        verify(orderItems, never()).save(any());
        Order order = new Order();
        order.setId(77L);
        order.setCustomer(customer);
        order.setDeliveryDate(quote.date());
        when(orderItems.findAllByOrderId(77L)).thenReturn(List.of(orderLine(order, quantity, bread.getPrice())));
        orderService().recalculate(order);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(quote.totalAmount());
        assertThat(order.getDiscountAmount()).isEqualByComparingTo(quote.discountAmount());
        assertThat(order.getDiscountLabel()).isEqualTo(quote.discountLabel());
        assertThat(order.getBatchDiscountAmount()).isEqualByComparingTo(quote.batchDiscountAmount());
        assertThat(order.getBatchDiscountLabel()).isEqualTo(quote.batchDiscountLabel());
        assertThat(order.getDeliveryCharge()).isEqualByComparingTo(quote.deliveryCharge());
        assertThat(order.getGiftLabel()).isEqualTo(quote.giftLabel());
        assertThat(order.getCreditApplied()).isEqualByComparingTo(quote.creditApplied());
        verifyNoInteractions(payments, gateway);
    }

    private void launchOffers() {
        Offer discount = offer(OfferKind.PERCENT_OFF, OfferGroup.DISCOUNT, "Launch offer", "20", null);
        Offer delivery = offer(OfferKind.FREE_DELIVERY_OVER, OfferGroup.DELIVERY, "Free delivery", null, "550");
        Offer gift = offer(OfferKind.FREE_ITEM_OVER, OfferGroup.GIFT, "Gift", null, "450");
        gift.setGiftLabel("Complimentary roll");
        when(offers.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of(discount, delivery, gift));
    }

    private Offer offer(OfferKind kind, OfferGroup group, String label, String percent, String threshold) {
        Offer offer = new Offer();
        offer.setKind(kind);
        offer.setStackGroup(group);
        offer.setLabel(label);
        offer.setPercent(percent == null ? null : new BigDecimal(percent));
        offer.setThresholdAmount(threshold == null ? null : new BigDecimal(threshold));
        return offer;
    }

    private void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    private void assertFields(JsonNode node, Set<String> expected) {
        List<String> actual = new ArrayList<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

}
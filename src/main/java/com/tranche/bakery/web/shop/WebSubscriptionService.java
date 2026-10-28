package com.tranche.bakery.web.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.order.CapacityLock;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.subscription.Subscription;
import com.tranche.bakery.subscription.SubscriptionCatalog;
import com.tranche.bakery.subscription.SubscriptionCatalog.ComponentConfig;
import com.tranche.bakery.subscription.SubscriptionCatalog.OptionConfig;
import com.tranche.bakery.subscription.SubscriptionCatalog.PlanConfig;
import com.tranche.bakery.subscription.SubscriptionRepository;
import com.tranche.bakery.subscription.SubscriptionService;
import com.tranche.bakery.subscription.SubscriptionService.ChosenItem;
import com.tranche.bakery.subscription.SubscriptionStatus;
import com.tranche.bakery.web.auth.CustomerSessionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Website sign-up for the weekly subscription. Plans are public; F&F plans are only offered to a
 * signed-in F&F customer. Payment is a Razorpay link whose webhook activates the subscription
 * through the same path as WhatsApp. Changes after payment stay with the bakery.
 */
@Service
public class WebSubscriptionService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Same days WhatsApp offers (no Wednesday). */
    public static final List<DayOfWeek> DAYS = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    private static final Set<String> FIELDS = Set.of("planCode", "option", "items", "deliveryDay",
            "name", "address", "deliveryPreference", "loafPreference");
    // Unpaid requests are cancelled by the 20:00 job (those created before 18:00); links lapse just before.
    private static final LocalTime STALE_CUTOFF = LocalTime.of(18, 0);
    private static final LocalTime LINK_EXPIRY = LocalTime.of(19, 45);

    private final JdbcTemplate jdbc;
    private final WebCartService carts;
    private final CustomerSessionService sessions;
    private final CapacityLock capacity;
    private final SubscriptionCatalog catalog;
    private final SubscriptionService subscriptions;
    private final SubscriptionRepository repository;
    private final MenuItemRepository menu;
    private final RazorpayService razorpay;
    private final AlertService alerts;
    private final Clock clock;
    private final TransactionTemplate tx;
    @PersistenceContext private EntityManager entities;

    public WebSubscriptionService(JdbcTemplate jdbc, WebCartService carts, CustomerSessionService sessions,
            CapacityLock capacity, SubscriptionCatalog catalog, SubscriptionService subscriptions,
            SubscriptionRepository repository, MenuItemRepository menu, RazorpayService razorpay,
            AlertService alerts, Clock webAuthClock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.carts = carts; this.sessions = sessions; this.capacity = capacity;
        this.catalog = catalog; this.subscriptions = subscriptions; this.repository = repository;
        this.menu = menu; this.razorpay = razorpay; this.alerts = alerts; this.clock = webAuthClock;
        this.tx = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    public record Plans(String audience, List<Plan> plans, List<Day> days) { }
    public record Plan(String code, String name, String tier, BigDecimal weeklyPrice, BigDecimal deliveryCharge,
                       int paidWeeks, int freeWeeks, BigDecimal upfront, List<Option> options) { }
    public record Option(int index, String label, String description, List<Component> components) { }
    public record Component(String type, String portion, int quantity, List<String> choices) { }
    public record Day(DayOfWeek day, LocalDate firstDelivery) { }

    public record Current(View subscription) { }
    public record View(String status, String channel, String planName, String tier, BigDecimal weeklyPrice,
                       BigDecimal deliveryCharge, int paidWeeks, int freeWeeks, BigDecimal creditApplied,
                       BigDecimal amount, DayOfWeek deliveryDay, LocalDate startDate, LocalDate endDate,
                       List<Item> items, List<Delivery> deliveries, Fulfillment fulfillment,
                       String paymentUrl, BigDecimal savings) { }
    public record Item(String name, int quantity, String portion) { }
    public record Delivery(int week, LocalDate date, String status) { }
    public record Fulfillment(String name, String address, String deliveryPreference, String loafPreference) { }

    private record Input(String planCode, int option, List<String> items, DayOfWeek day, String name,
                         String address, String delivery, String loaf) { }
    private record Dispatch(long id, BigDecimal amount, String name, String phone, LocalDateTime created) { }
    private record Target(long id, String linkId) { }

    /** Anonymous visitors see public plans; a signed-in F&F customer sees the F&F plans instead. */
    public Plans plans(HttpServletRequest request) {
        return Objects.requireNonNull(tx.execute(status -> {
            Customer customer = sessions.currentCustomer(request);
            boolean ff = customer != null && customer.isFriendsAndFamily();
            Map<String, Boolean> active = new HashMap<>();
            List<Plan> plans = new ArrayList<>();
            for (PlanConfig plan : catalog.activePlansFor(ff)) {
                List<Option> options = new ArrayList<>();
                List<OptionConfig> configured = plan.getOptions() == null ? List.of() : plan.getOptions();
                for (int i = 0; i < configured.size(); i++) {
                    OptionConfig option = configured.get(i);
                    List<Component> components = new ArrayList<>();
                    for (ComponentConfig component : option.getComponents()) {
                        List<String> choices = catalog.chooseFrom(component, plan.getTier()).stream()
                                .filter(name -> active.computeIfAbsent(name,
                                        n -> menu.findFirstByNameAndActiveTrue(n).isPresent()))
                                .toList();
                        components.add(new Component(component.getType(), component.getPortion(), component.getQty(), choices));
                    }
                    // An option with a component nobody can currently pick is not offered.
                    if (!components.isEmpty() && components.stream().noneMatch(c -> c.choices().isEmpty()))
                        options.add(new Option(i, option.getLabel(), option.getDescription(), components));
                }
                if (!options.isEmpty()) plans.add(new Plan(plan.getCode(), plan.getName(), plan.getTier(),
                        plan.getWeeklyPrice(), catalog.effectiveDeliveryCharge(plan), plan.getCommitmentWeeks(),
                        Math.max(0, plan.getBonusWeeks()), catalog.totalUpfront(plan), options));
            }
            List<Day> days = DAYS.stream().map(day -> new Day(day, subscriptions.firstDeliveryFor(day))).toList();
            return new Plans(ff ? "FF" : "PUBLIC", plans, days);
        }));
    }

    public Current current(HttpServletRequest request) {
        return Objects.requireNonNull(tx.execute(status -> {
            // Open-in-view keeps entities loaded earlier in this request; the link/cancel writes are JDBC.
            entities.clear();
            Customer customer = carts.requireCustomer(request);
            Subscription sub = open(customer.getId());
            return new Current(sub == null ? null : view(sub));
        }));
    }

    public Current create(JsonNode body, HttpServletRequest request) {
        Dispatch dispatch = Objects.requireNonNull(tx.execute(status -> accept(body, request)));
        dispatch(dispatch);
        return current(request);
    }

    private Dispatch accept(JsonNode body, HttpServletRequest request) {
        capacity.acquire(); // Global order: capacity -> customer -> subscription.
        Customer customer = carts.requireCustomer(request);
        Input input = input(body);
        PlanConfig plan = catalog.plan(input.planCode())
                .filter(PlanConfig::isActive)
                .filter(p -> (customer.isFriendsAndFamily() ? "FF" : "PUBLIC").equalsIgnoreCase(p.getAudience()))
                .orElseThrow(() -> conflict("PLAN_UNAVAILABLE", "Choose one of the plans currently offered."));
        List<OptionConfig> options = plan.getOptions() == null ? List.of() : plan.getOptions();
        if (input.option() >= options.size()) throw invalid();
        List<ComponentConfig> components = options.get(input.option()).getComponents();
        if (components == null || components.size() != input.items().size()) throw invalid();
        List<ChosenItem> chosen = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            ComponentConfig component = components.get(i);
            String name = input.items().get(i);
            if (!catalog.chooseFrom(component, plan.getTier()).contains(name)) throw invalid();
            if (menu.findFirstByNameAndActiveTrue(name).isEmpty())
                throw conflict("ITEM_UNAVAILABLE", "A chosen bake is no longer available.");
            chosen.add(new ChosenItem(name, component.getQty(), component.getPortion()));
        }
        if (open(customer.getId()) != null)
            throw conflict("SUBSCRIPTION_EXISTS", "You already have a subscription in progress.");
        if (positive(customer.getCreditBalance()))
            throw conflict("CREDIT_SUPPORT_REQUIRED", "Contact the bakery to use or review account credit.");
        if (!razorpay.isGatewayMode() || !razorpay.isConfigured() || !razorpay.webhookConfigured())
            throw new StorefrontProblemException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_UNAVAILABLE",
                    "Online payment is temporarily unavailable.");
        Subscription sub = subscriptions.createPending(customer, plan.getCode(), chosen, input.day());
        if (!positive(sub.getUpfrontAmount()))
            throw conflict("ZERO_PAYABLE_SUPPORT_REQUIRED", "This subscription needs support review.");
        sub.setChannel("WEB");
        sub.setDeliveryName(input.name());
        sub.setDeliveryAddress(input.address());
        sub.setDeliveryPreference(input.delivery());
        sub.setLoafPreference(input.loaf());
        // The webhook verifies the paid amount against this; web links never use payment test mode.
        sub.setGatewayChargedAmount(sub.getUpfrontAmount());
        sub = repository.save(sub);
        // Only fills a blank name; names set on WhatsApp or by admin are never overwritten.
        jdbc.update("UPDATE customers SET name=?, updated_at=now() WHERE id=? AND (name IS NULL OR TRIM(name)='')",
                input.name(), customer.getId());
        return new Dispatch(sub.getId(), sub.getUpfrontAmount(), input.name(), customer.getPhone(), sub.getCreatedAt());
    }

    // Provider I/O stays outside the DB transaction. A failure leaves the request pending without a
    // link; the customer can cancel it and the evening job retires it.
    private void dispatch(Dispatch dispatch) {
        RazorpayService.PaymentLink link;
        try {
            link = razorpay.createPaymentLink(dispatch.amount(), "Tranche Bakery Subscription " + dispatch.id(),
                    "SUB-" + dispatch.id(), dispatch.name(), dispatch.phone(),
                    Map.of("kind", "SUBSCRIPTION", "subscriptionId", String.valueOf(dispatch.id())),
                    linkExpiry(dispatch.created()));
        } catch (Exception ex) {
            linkFailed(dispatch, "create failed: " + ex.getClass().getSimpleName());
            return;
        }
        if (link == null || link.id() == null || !link.id().matches("plink_[A-Za-z0-9_]{1,94}")
                || !WebCheckoutService.safeUrl(link.shortUrl())) {
            linkFailed(dispatch, "invalid link response");
            return;
        }
        tx.executeWithoutResult(status -> {
            capacity.acquire();
            jdbc.update("""
                    UPDATE subscriptions SET gateway_link_id=?,gateway_link_url=?,updated_at=now()
                    WHERE id=? AND status='PENDING_PAYMENT' AND gateway_link_id IS NULL
                    """, link.id(), link.shortUrl(), dispatch.id());
        });
    }

    private void linkFailed(Dispatch dispatch, String reason) {
        alerts.raise("PAYMENT_LINK_FAILED", "Website subscription " + dispatch.id()
                + ": payment link " + reason + ". Left pending; a late payment needs manual handling.",
                null, dispatch.phone());
    }

    /** Customer cancels an unpaid request. The link is retired first so it can never be paid afterwards. */
    public Current cancel(HttpServletRequest request) {
        Target target = Objects.requireNonNull(tx.execute(status -> {
            capacity.acquire();
            Customer customer = carts.requireCustomer(request);
            Subscription sub = open(customer.getId());
            if (sub == null) throw new StorefrontProblemException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND",
                    "No subscription request to cancel.");
            if (sub.getStatus() != SubscriptionStatus.PENDING_PAYMENT)
                throw conflict("SUBSCRIPTION_ACTIVE", "Paid subscriptions are changed by the bakery.");
            if (sub.getPaymentScreenshotMediaId() != null)
                throw conflict("CANCEL_UNCONFIRMED", "A payment is being checked.");
            return new Target(sub.getId(), sub.getGatewayLinkId());
        }));
        if (target.linkId() != null && !retire(target.linkId()))
            throw conflict("CANCEL_UNCONFIRMED", "The payment link could not be cancelled.");
        tx.executeWithoutResult(status -> {
            capacity.acquire();
            Customer customer = carts.requireCustomer(request);
            jdbc.update("""
                    UPDATE subscriptions SET status='CANCELLED',updated_at=now()
                    WHERE id=? AND customer_id=? AND status='PENDING_PAYMENT'
                    """, target.id(), customer.getId());
        });
        return current(request);
    }

    // True only when the link can no longer take a payment and none was taken.
    private boolean retire(String linkId) {
        try {
            razorpay.cancelPaymentLink(linkId);
            return true;
        } catch (Exception cancelFailed) {
            try {
                RazorpayService.LinkState state = razorpay.fetchPaymentLink(linkId);
                return state != null && state.paymentId() == null
                        && Set.of("cancelled", "expired").contains(state.status());
            } catch (Exception fetchFailed) {
                return false;
            }
        }
    }

    private Subscription open(long customerId) {
        return repository.findAllByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .filter(s -> s.getStatus() == SubscriptionStatus.PENDING_PAYMENT || s.getStatus() == SubscriptionStatus.ACTIVE)
                .findFirst().orElse(null);
    }

    private View view(Subscription sub) {
        boolean pending = sub.getStatus() == SubscriptionStatus.PENDING_PAYMENT;
        int total = sub.getCommitmentWeeks() + Math.max(0, sub.getBonusWeeks());
        Map<Integer, String> generated = new HashMap<>();
        jdbc.query("""
                SELECT p.week_number,o.status FROM subscription_periods p LEFT JOIN orders o ON o.id=p.order_id
                WHERE p.subscription_id=?
                """, rs -> { generated.put(rs.getInt(1), rs.getString(2)); }, sub.getId());
        // Unpaid: the dates it would get if paid now. Paid: the dates fixed at activation.
        LocalDate first = pending || sub.getStartDate() == null
                ? subscriptions.firstDeliveryFor(sub.getDeliveryDay()) : sub.getStartDate();
        List<Delivery> deliveries = new ArrayList<>();
        for (int week = 1; week <= total; week++) {
            String state = generated.get(week);
            deliveries.add(new Delivery(week, first.plusWeeks(week - 1L), state == null ? "SCHEDULED" : state));
        }
        boolean web = "WEB".equals(sub.getChannel());
        String url = pending && WebCheckoutService.safeUrl(sub.getGatewayLinkUrl())
                && (!web || clock.instant().isBefore(linkExpiry(sub.getCreatedAt()))) ? sub.getGatewayLinkUrl() : null;
        Fulfillment fulfillment = web && sub.getDeliveryAddress() != null ? new Fulfillment(sub.getDeliveryName(),
                sub.getDeliveryAddress(), sub.getDeliveryPreference(), sub.getLoafPreference()) : null;
        List<Item> items = sub.getItems().stream().map(i -> new Item(i.getItemName(), i.getQuantity(), i.getPortion())).toList();
        return new View(sub.getStatus().name(), web ? "WEB" : "WHATSAPP", sub.getPlanName(), sub.getTier(),
                sub.getWeeklyPrice(), sub.getDeliveryCharge(), sub.getCommitmentWeeks(), Math.max(0, sub.getBonusWeeks()),
                sub.getCreditApplied(), sub.getUpfrontAmount(), sub.getDeliveryDay(),
                pending ? null : sub.getStartDate(), pending ? null : sub.getEndDate(),
                items, deliveries, fulfillment, url, subscriptions.savingsFor(sub));
    }

    // createdAt is IST wall-clock (JVM default zone), matching expireStalePendingPayments.
    static Instant linkExpiry(LocalDateTime created) {
        LocalDate day = created.toLocalTime().isBefore(STALE_CUTOFF) ? created.toLocalDate() : created.toLocalDate().plusDays(1);
        return day.atTime(LINK_EXPIRY).atZone(IST).toInstant();
    }

    private Input input(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != FIELDS.size()) throw invalid();
        var names = body.fieldNames();
        while (names.hasNext()) if (!FIELDS.contains(names.next())) throw invalid();
        String planCode = text(body, "planCode");
        if (!planCode.matches("[A-Z0-9_]{1,50}")) throw invalid();
        JsonNode option = body.get("option");
        if (!option.isInt() || option.intValue() < 0 || option.intValue() > 20) throw invalid();
        JsonNode itemsNode = body.get("items");
        if (!itemsNode.isArray() || itemsNode.isEmpty() || itemsNode.size() > 6) throw invalid();
        List<String> items = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            if (!item.isTextual() || item.textValue().isEmpty() || item.textValue().length() > 100) throw invalid();
            items.add(item.textValue());
        }
        String dayText = text(body, "deliveryDay");
        DayOfWeek day = DAYS.stream().filter(d -> d.name().equals(dayText)).findFirst()
                .orElseThrow(WebSubscriptionService::invalid);
        String name = bounded(body, "name", 1, 100);
        String address = bounded(body, "address", 10, 2000);
        String delivery = text(body, "deliveryPreference"), loaf = text(body, "loafPreference");
        if (!Set.of("GATE", "DOOR", "IN_PERSON").contains(delivery) || !Set.of("SLICED", "WHOLE").contains(loaf)) throw invalid();
        return new Input(planCode, option.intValue(), List.copyOf(items), day, name, address, delivery, loaf);
    }

    private static String bounded(JsonNode body, String field, int min, int max) {
        String raw = text(body, field);
        if (raw.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)) throw invalid();
        String value = raw.strip();
        if (value.length() < min || value.length() > max) throw invalid();
        return value;
    }
    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static StorefrontProblemException invalid() {
        return new StorefrontProblemException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Supply valid subscription details.");
    }
    private static StorefrontProblemException conflict(String code, String message) {
        return new StorefrontProblemException(HttpStatus.CONFLICT, code, message);
    }
}

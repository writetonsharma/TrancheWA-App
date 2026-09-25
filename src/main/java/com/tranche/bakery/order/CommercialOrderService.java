package com.tranche.bakery.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.payment.QrCodeService;
import com.tranche.bakery.receipt.ReceiptPdfService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates and manages manually-entered commercial / bulk orders that bypass the retail
 * WhatsApp flow: admin enters buyer + line items (unit price x qty) + delivery charge,
 * we generate a bill-of-supply invoice with a UPI QR, and on payment produce a receipt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommercialOrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final MenuItemRepository menuItemRepository;
    private final CustomerRepository customerRepository;
    private final OrderNumberGenerator orderNumberGenerator;
    private final ReceiptPdfService receiptPdfService;
    private final QrCodeService qrCodeService;

    @Value("${bakery.payment.upi-id}")
    private String upiId;

    @Value("${bakery.payment.upi-name}")
    private String upiName;

    @Value("${bakery.seller.individual.name:Naveen Sharma}")
    private String individualName;

    @Value("${bakery.seller.individual.registration-label:Udyam Reg. No}")
    private String individualRegLabel;

    @Value("${bakery.seller.individual.registration-number:UDYAM-HR-05-0208872}")
    private String individualRegNumber;

    @Value("${bakery.seller.individual.upi-id:9811843373@ptsbi}")
    private String individualUpiId;

    @Value("${bakery.seller.individual.upi-name:NAVEEN SHARMA}")
    private String individualUpiName;

    /** One requested line: a menu item, the admin-entered unit price, the number of units, and whether it's a free gift. */
    public record Line(Long menuItemId, BigDecimal unitPrice, int quantity, boolean complimentary) {}

    /** Flat, session-free projection for the commercial orders list (built inside the transaction). */
    public record CommercialOrderView(Long id, String invoiceNumber, String businessName,
                                      String customerName, String customerPhone, LocalDate deliveryDate,
                                      OrderStatus status, BigDecimal totalAmount, int itemCount,
                                      SellerProfileType sellerProfile) {}

    @Transactional
    public Order createInvoice(String name, String phone, String businessName,
                               LocalDate deliveryDate, String deliveryAddress, String notes,
                               BigDecimal deliveryCharge, SellerProfileType sellerProfile, List<Line> lines) {
        String normalizedPhone = normalizePhone(phone);
        Customer customer = customerRepository.findByPhone(normalizedPhone).orElse(null);
        if (customer == null) {
            customer = new Customer();
            customer.setPhone(normalizedPhone);
            customer.setName(name != null && !name.isBlank() ? name.trim() : "Commercial customer");
            customer = customerRepository.save(customer);
        } else if (name != null && !name.isBlank()
                && (customer.getName() == null || customer.getName().isBlank())) {
            customer.setName(name.trim());
            customer = customerRepository.save(customer);
        }

        Order order = new Order();
        order.setCustomer(customer);
        order.setSource(OrderSource.COMMERCIAL);
        order.setStatus(OrderStatus.INVOICED);
        order.setFulfillmentType(FulfillmentType.DELIVERY);
        order.setDeliveryDate(deliveryDate);
        order.setDeliveryAddress(deliveryAddress);
        order.setNotes(notes);
        order.setBusinessName(businessName != null && !businessName.isBlank() ? businessName.trim() : null);
        order.setDeliveryCharge(deliveryCharge != null ? deliveryCharge : BigDecimal.ZERO);
        order.setSellerProfile(sellerProfile != null ? sellerProfile : SellerProfileType.COMPANY);
        order = orderRepository.save(order); // persist to obtain the id used for the invoice number

        BigDecimal itemsTotal = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>();
        for (Line ln : lines) {
            if (ln == null || ln.menuItemId() == null || ln.quantity() <= 0) continue;
            MenuItem mi = menuItemRepository.findById(ln.menuItemId()).orElse(null);
            if (mi == null) continue;
            boolean comp = ln.complimentary();
            if (!comp && ln.unitPrice() == null) continue; // a paid line needs a price
            BigDecimal unit = comp ? BigDecimal.ZERO.setScale(2) : ln.unitPrice().setScale(2, RoundingMode.HALF_UP);
            BigDecimal sub = unit.multiply(BigDecimal.valueOf(ln.quantity()));
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setMenuItem(mi);
            item.setQuantity(ln.quantity());
            item.setUnitPrice(unit);
            item.setListUnitPrice(mi.getPrice()); // snapshot list price so the bill can show the discount / gift value
            if (comp) item.setNote("Complimentary");
            item.setSubtotal(sub);
            items.add(item);
            itemsTotal = itemsTotal.add(sub);
        }
        orderItemRepository.saveAll(items);

        order.setTotalAmount(itemsTotal.add(order.getDeliveryCharge()));
        order.setInvoiceNumber(
                orderNumberGenerator.generate(order.getId(), order.getCreatedAt()).replaceFirst("TRB-", "TRB-INV-"));
        order = orderRepository.save(order);
        log.info("Created commercial invoice {} (order {}) total {}",
                order.getInvoiceNumber(), order.getId(), order.getTotalAmount());
        return order;
    }

    /** Admin confirms payment received — order moves to CONFIRMED and joins the bake list (capacity not enforced). */
    @Transactional
    public void markPaid(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            if (order.getSource() != OrderSource.COMMERCIAL) return;
            order.setStatus(OrderStatus.CONFIRMED);
            if (order.getOrderNumber() == null) {
                order.setOrderNumber(orderNumberGenerator.generate(order.getId(), order.getCreatedAt()));
            }
            orderRepository.save(order);
            log.info("Commercial invoice {} marked paid -> CONFIRMED", order.getInvoiceNumber());
        });
    }

    @Transactional
    public void cancel(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            if (order.getSource() != OrderSource.COMMERCIAL) return;
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
        });
    }

    @Transactional(readOnly = true)
    public byte[] invoicePdf(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getSource() != OrderSource.COMMERCIAL) return null;
        byte[] qr = null;
        BigDecimal amount = order.getTotalAmount();
        boolean individual = order.getSellerProfile() == SellerProfileType.INDIVIDUAL;
        if (amount != null && amount.signum() > 0) {
            String note = ("Invoice " + (order.getInvoiceNumber() != null ? order.getInvoiceNumber() : order.getId()))
                    .replaceAll("[^A-Za-z0-9 ]", " ");
            qr = qrCodeService.generateUpiQrPng(
                    individual ? individualUpiId : upiId,
                    individual ? individualUpiName : upiName,
                    amount, note);
        }
        return receiptPdfService.buildInvoice(order, order.getInvoiceNumber(), qr, sellerLine(order));
    }

    @Transactional(readOnly = true)
    public byte[] receiptPdf(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getSource() != OrderSource.COMMERCIAL) return null;
        return receiptPdfService.build(order, sellerLine(order));
    }

    /** "Seller / Billed by" line for the individual identity; null for the company (default) profile. */
    private String sellerLine(Order order) {
        if (order.getSellerProfile() != SellerProfileType.INDIVIDUAL) return null;
        return "Seller: " + individualName + "  \u00b7  " + individualRegLabel + ": " + individualRegNumber;
    }

    @Transactional(readOnly = true)
    public List<CommercialOrderView> listCommercial() {
        List<Order> orders = orderRepository.findAllBySourceOrderByCreatedAtDesc(OrderSource.COMMERCIAL);
        List<CommercialOrderView> views = new ArrayList<>();
        for (Order o : orders) {
            Customer c = o.getCustomer();
            int count = orderItemRepository.findAllByOrderId(o.getId()).size();
            views.add(new CommercialOrderView(o.getId(), o.getInvoiceNumber(), o.getBusinessName(),
                    c != null ? c.getName() : null, c != null ? c.getPhone() : null,
                    o.getDeliveryDate(), o.getStatus(), o.getTotalAmount(), count, o.getSellerProfile()));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public Order find(Long orderId) {
        return orderRepository.findById(orderId).orElse(null);
    }

    private static String normalizePhone(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("\\D", "");
        return digits.length() == 10 ? "91" + digits : digits;
    }
}

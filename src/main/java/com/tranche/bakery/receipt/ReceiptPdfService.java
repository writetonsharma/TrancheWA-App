package com.tranche.bakery.receipt;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.FulfillmentType;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.subscription.Subscription;
import com.tranche.bakery.subscription.SubscriptionItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Builds a branded, downloadable PDF receipt for a paid order. Uses OpenPDF
 * (pure Java, no native deps). The rupee sign is written as "Rs." because the
 * built-in PDF Helvetica font does not carry the Indian rupee glyph.
 */
@Service
@RequiredArgsConstructor
public class ReceiptPdfService {

    private final ReceiptProperties props;
    private final OrderItemRepository orderItemRepository;

    private static final Color INK = new Color(0x2b, 0x21, 0x1a);
    private static final Color MUTED = new Color(0x7a, 0x6f, 0x64);
    private static final Color LINE = new Color(0xd8, 0xcf, 0xc4);
    private static final Color ACCENT = new Color(0x8a, 0x5a, 0x2b);
    private static final Color PAID = new Color(0x2e, 0x7d, 0x32);

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    public byte[] build(Order order) {
        Document doc = new Document(PageSize.A4, 48, 48, 46, 46);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();

        header(doc);
        titleRow(doc, order);
        parties(doc, order);
        itemsTable(doc, order);
        footer(doc);

        doc.close();
        return out.toByteArray();
    }

    /** Receipt for a prepaid weekly subscription (upfront payment for the whole commitment). */
    public byte[] build(Subscription sub, String receiptNo) {
        Document doc = new Document(PageSize.A4, 48, 48, 46, 46);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();

        header(doc);
        subscriptionTitleRow(doc, receiptNo);
        subscriptionParties(doc, sub);
        subscriptionBody(doc, sub);
        footer(doc);

        doc.close();
        return out.toByteArray();
    }

    private void header(Document doc) {
        Font nameF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, ACCENT);
        Paragraph name = new Paragraph(props.getBusinessName(), nameF);
        name.setSpacingAfter(1f);

        Paragraph subLine = null;
        StringBuilder sub = new StringBuilder();
        if (notBlank(props.getTagline())) sub.append(props.getTagline());
        if (notBlank(props.getLocation())) {
            if (sub.length() > 0) sub.append("  |  ");
            sub.append(props.getLocation());
        }
        if (sub.length() > 0) {
            subLine = new Paragraph(sub.toString(), FontFactory.getFont(FontFactory.HELVETICA, 9.5f, MUTED));
        }
        Paragraph contactLine = null;
        StringBuilder line2 = new StringBuilder();
        if (notBlank(props.getContactPhone())) line2.append("Contact: ").append(props.getContactPhone());
        if (notBlank(props.getFssai())) {
            if (line2.length() > 0) line2.append("   ");
            line2.append("FSSAI Lic. No: ").append(props.getFssai());
        }
        if (line2.length() > 0) {
            contactLine = new Paragraph(line2.toString(), FontFactory.getFont(FontFactory.HELVETICA, 9.5f, MUTED));
        }

        com.lowagie.text.Image logo = loadLogo();
        if (logo != null) {
            logo.scaleToFit(54, 54);
            PdfPTable t = new PdfPTable(new float[]{ 1.1f, 8.9f });
            t.setWidthPercentage(100);
            t.setSpacingAfter(8f);
            PdfPCell logoCell = new PdfPCell(logo, false);
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            t.addCell(logoCell);
            PdfPCell textCell = new PdfPCell();
            textCell.setBorder(Rectangle.NO_BORDER);
            textCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            textCell.addElement(name);
            if (subLine != null) textCell.addElement(subLine);
            if (contactLine != null) textCell.addElement(contactLine);
            t.addCell(textCell);
            doc.add(t);
        } else {
            doc.add(name);
            if (subLine != null) doc.add(subLine);
            if (contactLine != null) { contactLine.setSpacingAfter(8f); doc.add(contactLine); }
        }
        doc.add(divider());
    }

    // Optional brand logo for the receipt header; never blocks a receipt if missing.
    private com.lowagie.text.Image loadLogo() {
        try {
            byte[] bytes = new org.springframework.core.io.ClassPathResource("receipt/logo.png")
                    .getInputStream().readAllBytes();
            return com.lowagie.text.Image.getInstance(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private void titleRow(Document doc, Order order) {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setSpacingBefore(10f);
        t.setSpacingAfter(6f);

        Font titleF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, INK);
        PdfPCell left = borderless(new Phrase("RECEIPT", titleF));

        Font paidF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, PAID);
        PdfPCell right = borderless(new Phrase("PAID", paidF));
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);

        t.addCell(left);
        t.addCell(right);
        doc.add(t);

        Font metaF = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, MUTED);
        String receiptNo = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
        doc.add(new Paragraph("Receipt No: " + receiptNo, metaF));
        doc.add(new Paragraph("Issued: " + LocalDateTime.now().format(STAMP_FMT), metaF));
    }

    private void parties(Document doc, Order order) {
        Customer c = order.getCustomer();
        Font labelF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, INK);
        Font valF = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, INK);

        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setSpacingBefore(10f);
        t.setSpacingAfter(4f);

        StringBuilder bill = new StringBuilder();
        String cname = c != null && notBlank(c.getName()) ? c.getName() : "Customer";
        bill.append(cname);
        if (c != null && notBlank(c.getPhone())) bill.append("\n").append(c.getPhone());

        StringBuilder deliver = new StringBuilder();
        if (order.getFulfillmentType() == FulfillmentType.DELIVERY) {
            deliver.append("Delivery");
            if (order.getDeliveryDate() != null)
                deliver.append("\n").append(order.getDeliveryDate().format(DATE_FMT));
            if (notBlank(order.getDeliveryAddress()))
                deliver.append("\n").append(order.getDeliveryAddress());
        } else {
            deliver.append("Pickup");
            if (order.getDeliveryDate() != null)
                deliver.append("\n").append(order.getDeliveryDate().format(DATE_FMT));
        }

        t.addCell(labelledCell("Billed to", bill.toString(), labelF, valF, Element.ALIGN_LEFT));
        t.addCell(labelledCell("Fulfilment", deliver.toString(), labelF, valF, Element.ALIGN_RIGHT));
        doc.add(t);
    }

    private void itemsTable(Document doc, Order order) {
        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        Customer c = order.getCustomer();
        boolean override = c != null && c.hasActiveOverride();

        PdfPTable t = new PdfPTable(new float[]{ 6f, 1.4f, 2.4f });
        t.setWidthPercentage(100);
        t.setSpacingBefore(12f);

        t.addCell(headCell("Item", Element.ALIGN_LEFT));
        t.addCell(headCell("Qty", Element.ALIGN_CENTER));
        t.addCell(headCell("Amount", Element.ALIGN_RIGHT));

        for (OrderItem it : items) {
            BigDecimal unit = override ? c.unitPriceFor(itemName(it), categoryName(it)) : null;
            BigDecimal lineAmt = unit != null
                    ? unit.multiply(BigDecimal.valueOf(it.getQuantity()))
                    : it.getSubtotal();
            String name = it.getMenuItem().getName();
            if (unit != null && lineAmt.compareTo(it.getSubtotal()) < 0) {
                name = name + "  (was " + money(it.getSubtotal()) + ")";
            }
            t.addCell(bodyCell(name, Element.ALIGN_LEFT));
            t.addCell(bodyCell(String.valueOf(it.getQuantity()), Element.ALIGN_CENTER));
            t.addCell(bodyCell(money(lineAmt), Element.ALIGN_RIGHT));
        }
        doc.add(t);

        // Totals block
        PdfPTable tot = new PdfPTable(new float[]{ 6.6f, 3.2f });
        tot.setWidthPercentage(100);
        tot.setSpacingBefore(2f);

        if (!override && positive(order.getDiscountAmount())) {
            String label = notBlank(order.getDiscountLabel()) ? order.getDiscountLabel() : "Discount";
            totalRow(tot, label, "- " + money(order.getDiscountAmount()), false);
        }
        if (!override && positive(order.getBatchDiscountAmount())) {
            String label = notBlank(order.getBatchDiscountLabel()) ? order.getBatchDiscountLabel() : "Batch discount";
            totalRow(tot, label, "- " + money(order.getBatchDiscountAmount()), false);
        }
        if (order.getFulfillmentType() == FulfillmentType.DELIVERY) {
            if (positive(order.getDeliveryCharge()))
                totalRow(tot, "Delivery", money(order.getDeliveryCharge()), false);
            else
                totalRow(tot, "Delivery", "Free", false);
        }
        if (notBlank(order.getGiftLabel())) {
            totalRow(tot, order.getGiftLabel(), "Free gift", false);
        }
        totalRow(tot, "Total Paid", money(order.getTotalAmount()), true);
        doc.add(tot);

        BigDecimal savings = nz(order.getDiscountAmount()).add(nz(order.getBatchDiscountAmount()));
        if (savings.signum() > 0) {
            Paragraph saved = new Paragraph("You saved " + money(savings) + " on this order.",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f, PAID));
            saved.setSpacingBefore(6f);
            doc.add(saved);
        }
    }

    private void subscriptionTitleRow(Document doc, String receiptNo) {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setSpacingBefore(10f);
        t.setSpacingAfter(6f);

        Font titleF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, INK);
        PdfPCell left = borderless(new Phrase("SUBSCRIPTION RECEIPT", titleF));

        Font paidF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, PAID);
        PdfPCell right = borderless(new Phrase("PAID", paidF));
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);

        t.addCell(left);
        t.addCell(right);
        doc.add(t);

        Font metaF = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, MUTED);
        doc.add(new Paragraph("Receipt No: " + receiptNo, metaF));
        doc.add(new Paragraph("Issued: " + LocalDateTime.now().format(STAMP_FMT), metaF));
    }

    private void subscriptionParties(Document doc, Subscription sub) {
        Customer c = sub.getCustomer();
        Font labelF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, INK);
        Font valF = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, INK);

        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setSpacingBefore(10f);
        t.setSpacingAfter(4f);

        StringBuilder bill = new StringBuilder();
        String cname = c != null && notBlank(c.getName()) ? c.getName() : "Customer";
        bill.append(cname);
        if (c != null && notBlank(c.getPhone())) bill.append("\n").append(c.getPhone());
        if (c != null && notBlank(c.getDeliveryAddress())) bill.append("\n").append(c.getDeliveryAddress());

        StringBuilder plan = new StringBuilder();
        plan.append(sub.getPlanName()).append("\nWeekly subscription");
        if (sub.getDeliveryDay() != null)
            plan.append("\nEvery ").append(sub.getDeliveryDay().getDisplayName(TextStyle.FULL, Locale.ENGLISH));

        t.addCell(labelledCell("Billed to", bill.toString(), labelF, valF, Element.ALIGN_LEFT));
        t.addCell(labelledCell("Plan", plan.toString(), labelF, valF, Element.ALIGN_RIGHT));
        doc.add(t);
    }

    private void subscriptionBody(Document doc, Subscription sub) {
        int paidWeeks = sub.getCommitmentWeeks();
        int bonusWeeks = sub.getBonusWeeks();
        int totalWeeks = paidWeeks + bonusWeeks;
        BigDecimal weekly = sub.getWeeklyPrice() != null ? sub.getWeeklyPrice() : BigDecimal.ZERO;
        BigDecimal delivery = sub.getDeliveryCharge() != null ? sub.getDeliveryCharge() : BigDecimal.ZERO;

        PdfPTable t = new PdfPTable(new float[]{ 7.4f, 2.4f });
        t.setWidthPercentage(100);
        t.setSpacingBefore(12f);
        t.addCell(headCell("Weekly bundle", Element.ALIGN_LEFT));
        t.addCell(headCell("Qty", Element.ALIGN_CENTER));
        for (SubscriptionItem it : sub.getItems()) {
            String name = "HALF".equalsIgnoreCase(it.getPortion())
                    ? "\u00bd " + it.getItemName() + " (half loaf)"
                    : it.getItemName();
            t.addCell(bodyCell(name, Element.ALIGN_LEFT));
            t.addCell(bodyCell(String.valueOf(it.getQuantity()), Element.ALIGN_CENTER));
        }
        doc.add(t);

        PdfPTable tot = new PdfPTable(new float[]{ 6.6f, 3.2f });
        tot.setWidthPercentage(100);
        tot.setSpacingBefore(2f);
        totalRow(tot, "Bakes (Rs. " + plain(weekly) + " x " + paidWeeks + " weeks)",
                money(weekly.multiply(BigDecimal.valueOf(paidWeeks))), false);
        if (positive(delivery))
            totalRow(tot, "Delivery (Rs. " + plain(delivery) + " x " + totalWeeks + " weeks)",
                    money(delivery.multiply(BigDecimal.valueOf(totalWeeks))), false);
        else
            totalRow(tot, "Delivery", "Free", false);
        if (bonusWeeks > 0)
            totalRow(tot, "Bonus " + (bonusWeeks == 1 ? "week" : bonusWeeks + " weeks") + " bread", "Free", false);
        totalRow(tot, "Total Paid", money(sub.getUpfrontAmount()), true);
        doc.add(tot);

        BigDecimal regular = sub.getRegularValue();
        if (regular != null && regular.signum() > 0) {
            BigDecimal saving = regular.multiply(BigDecimal.valueOf(totalWeeks))
                    .subtract(weekly.multiply(BigDecimal.valueOf(paidWeeks)));
            if (saving.signum() > 0) {
                Paragraph saved = new Paragraph("You saved " + money(saving) + " vs buying these weekly at regular prices.",
                        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f, PAID));
                saved.setSpacingBefore(6f);
                doc.add(saved);
            }
        }

        String coverage = totalWeeks + " weekly deliveries"
                + (bonusWeeks > 0 ? " (including " + bonusWeeks + " free)" : "")
                + (sub.getStartDate() != null ? ", from " + sub.getStartDate().format(DATE_FMT) : "")
                + (sub.getEndDate() != null ? " to " + sub.getEndDate().format(DATE_FMT) : "") + ".";
        Paragraph note = new Paragraph(coverage, FontFactory.getFont(FontFactory.HELVETICA, 9f, MUTED));
        note.setSpacingBefore(8f);
        doc.add(note);
    }

    private void footer(Document doc) {
        Paragraph note = new Paragraph(
                "GST not applicable. Prices are inclusive of all charges shown.",
                FontFactory.getFont(FontFactory.HELVETICA, 8.5f, MUTED));
        note.setSpacingBefore(16f);
        doc.add(note);

        Paragraph thanks = new Paragraph("Thank you for ordering from " + props.getBusinessName() + ".",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10, ACCENT));
        thanks.setSpacingBefore(6f);
        doc.add(thanks);
    }

    // --- helpers ---

    private Paragraph divider() {
        Paragraph p = new Paragraph(new Chunk(new com.lowagie.text.pdf.draw.LineSeparator(
                0.6f, 100, LINE, Element.ALIGN_CENTER, -2)));
        p.setSpacingBefore(4f);
        return p;
    }

    private PdfPCell borderless(Phrase ph) {
        PdfPCell c = new PdfPCell(ph);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    private PdfPCell labelledCell(String label, String value, Font labelF, Font valF, int align) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        Paragraph l = new Paragraph(label, labelF);
        l.setAlignment(align);
        Paragraph v = new Paragraph(value, valF);
        v.setAlignment(align);
        v.setSpacingBefore(2f);
        c.addElement(l);
        c.addElement(v);
        return c;
    }

    private PdfPCell headCell(String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, Color.WHITE)));
        c.setBackgroundColor(ACCENT);
        c.setHorizontalAlignment(align);
        c.setPadding(6f);
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    private PdfPCell bodyCell(String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA, 9.5f, INK)));
        c.setHorizontalAlignment(align);
        c.setPadding(6f);
        c.setBorderColor(LINE);
        c.setBorderWidth(0.5f);
        c.setBorder(Rectangle.BOTTOM);
        return c;
    }

    private void totalRow(PdfPTable t, String label, String value, boolean bold) {
        Font f = bold
                ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, INK)
                : FontFactory.getFont(FontFactory.HELVETICA, 9.5f, INK);
        PdfPCell l = new PdfPCell(new Phrase(label, f));
        l.setHorizontalAlignment(Element.ALIGN_RIGHT);
        l.setBorder(bold ? Rectangle.TOP : Rectangle.NO_BORDER);
        l.setBorderColor(LINE);
        l.setPadding(5f);
        PdfPCell v = new PdfPCell(new Phrase(value, f));
        v.setHorizontalAlignment(Element.ALIGN_RIGHT);
        v.setBorder(bold ? Rectangle.TOP : Rectangle.NO_BORDER);
        v.setBorderColor(LINE);
        v.setPadding(5f);
        t.addCell(l);
        t.addCell(v);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String categoryName(OrderItem it) {
        return it.getMenuItem() != null && it.getMenuItem().getCategory() != null
                ? it.getMenuItem().getCategory().getName()
                : null;
    }

    private static String itemName(OrderItem it) {
        return it.getMenuItem() != null ? it.getMenuItem().getName() : null;
    }

    private static boolean positive(BigDecimal b) { return b != null && b.compareTo(BigDecimal.ZERO) > 0; }

    private static BigDecimal nz(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }

    private static String money(BigDecimal b) {
        if (b == null) b = BigDecimal.ZERO;
        return "Rs. " + b.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal b) {
        if (b == null) b = BigDecimal.ZERO;
        return b.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}

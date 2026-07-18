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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    private void header(Document doc) {
        Font nameF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, ACCENT);
        Paragraph name = new Paragraph(props.getBusinessName(), nameF);
        name.setSpacingAfter(1f);
        doc.add(name);

        StringBuilder sub = new StringBuilder();
        if (notBlank(props.getTagline())) sub.append(props.getTagline());
        if (notBlank(props.getLocation())) {
            if (sub.length() > 0) sub.append("  |  ");
            sub.append(props.getLocation());
        }
        if (sub.length() > 0) {
            doc.add(new Paragraph(sub.toString(),
                    FontFactory.getFont(FontFactory.HELVETICA, 9.5f, MUTED)));
        }
        StringBuilder line2 = new StringBuilder();
        if (notBlank(props.getContactPhone())) line2.append("Contact: ").append(props.getContactPhone());
        if (notBlank(props.getFssai())) {
            if (line2.length() > 0) line2.append("   ");
            line2.append("FSSAI Lic. No: ").append(props.getFssai());
        }
        if (line2.length() > 0) {
            Paragraph l2 = new Paragraph(line2.toString(),
                    FontFactory.getFont(FontFactory.HELVETICA, 9.5f, MUTED));
            l2.setSpacingAfter(8f);
            doc.add(l2);
        }
        doc.add(divider());
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
            BigDecimal lineAmt = override
                    ? c.getPricingOverride().multiply(BigDecimal.valueOf(it.getQuantity()))
                    : it.getSubtotal();
            t.addCell(bodyCell(it.getMenuItem().getName(), Element.ALIGN_LEFT));
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

    private static boolean positive(BigDecimal b) { return b != null && b.compareTo(BigDecimal.ZERO) > 0; }

    private static String money(BigDecimal b) {
        if (b == null) b = BigDecimal.ZERO;
        return "Rs. " + b.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}

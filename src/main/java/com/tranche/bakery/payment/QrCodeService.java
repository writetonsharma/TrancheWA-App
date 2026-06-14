package com.tranche.bakery.payment;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class QrCodeService {

    public byte[] generateUpiQrPng(String upiId, String payeeName, BigDecimal amount, String note) {
        try {
            String encoded = "upi://pay?pa=" + URLEncoder.encode(upiId, StandardCharsets.UTF_8)
                    + "&pn=" + URLEncoder.encode(payeeName, StandardCharsets.UTF_8)
                    + "&am=" + amount.toPlainString()
                    + "&cu=INR"
                    + "&tn=" + URLEncoder.encode(note, StandardCharsets.UTF_8);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(encoded, BarcodeFormat.QR_CODE, 512, 512);

            // Explicitly render as 24-bit RGB so WhatsApp accepts the image
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate UPI QR code", e);
        }
    }
}

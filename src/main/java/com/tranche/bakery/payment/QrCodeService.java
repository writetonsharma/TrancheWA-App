package com.tranche.bakery.payment;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

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

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate UPI QR code", e);
        }
    }
}

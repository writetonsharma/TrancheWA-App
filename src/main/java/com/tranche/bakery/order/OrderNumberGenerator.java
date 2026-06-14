package com.tranche.bakery.order;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class OrderNumberGenerator {

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public String generate(Long orderId, LocalDateTime createdAt) {
        String date = createdAt.format(DATE_FMT);
        String suffix = hashToSuffix(orderId, createdAt);
        return "TRB-" + date + "-" + suffix;
    }

    private String hashToSuffix(Long orderId, LocalDateTime createdAt) {
        long seed = orderId * 1_000_000_007L
                + createdAt.getSecond()
                + createdAt.getMinute() * 60L
                + createdAt.getHour() * 3600L
                + createdAt.getNano();
        // Mix bits
        seed ^= (seed >>> 30) * 0xbf58476d1ce4e5b9L;
        seed ^= (seed >>> 27) * 0x94d049bb133111ebL;
        seed ^= (seed >>> 31);
        long value = Math.abs(seed);
        int base = CHARS.length();
        char[] result = new char[4];
        for (int i = 3; i >= 0; i--) {
            result[i] = CHARS.charAt((int)(value % base));
            value /= base;
        }
        return new String(result);
    }
}

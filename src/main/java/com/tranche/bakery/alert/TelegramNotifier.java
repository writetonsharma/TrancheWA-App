package com.tranche.bakery.alert;

import java.net.URI;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends admin alerts to Telegram — a reliable channel that (unlike WhatsApp) is not subject
 * to the 24-hour re-engagement window, so notifications always get through. Enabled only when
 * both TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID are configured; otherwise every call is a no-op.
 */
@Component
@Slf4j
public class TelegramNotifier {

    private final String botToken;
    private final String chatId;
    private final RestClient restClient = RestClient.create();

    public TelegramNotifier(
            @Value("${telegram.bot-token:}") String botToken,
            @Value("${telegram.chat-id:}") String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
    }

    public boolean isEnabled() {
        return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
    }

    public void send(String text) {
        if (!isEnabled()) return;
        try {
            // Pre-built URI (not a template) so the ':' in the bot token isn't percent-encoded.
            URI uri = URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage");
            restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to send Telegram alert: {}", e.getMessage());
        }
    }
}

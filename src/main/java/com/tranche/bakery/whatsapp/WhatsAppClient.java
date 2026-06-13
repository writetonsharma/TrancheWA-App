package com.tranche.bakery.whatsapp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class WhatsAppClient {

    private final RestClient restClient;
    private final String phoneNumberId;

    public WhatsAppClient(
            @Value("${whatsapp.api.url}") String apiUrl,
            @Value("${whatsapp.api.token}") String token,
            @Value("${whatsapp.api.phone-number-id}") String phoneNumberId) {
        this.phoneNumberId = phoneNumberId;
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public void sendText(String to, String body) {
        send(WhatsAppMessage.text(to, body));
    }

    public void sendButtons(String to, String bodyText, java.util.List<WhatsAppMessage.Button> buttons) {
        send(WhatsAppMessage.buttonMessage(to, bodyText, buttons));
    }

    public void sendList(String to, String bodyText, String buttonLabel, java.util.List<WhatsAppMessage.Section> sections) {
        send(WhatsAppMessage.listMessage(to, bodyText, buttonLabel, sections));
    }

    private void send(Object message) {
        try {
            String response = restClient.post()
                    .uri("/{phoneNumberId}/messages", phoneNumberId)
                    .body(message)
                    .retrieve()
                    .body(String.class);
            log.debug("WhatsApp API response: {}", response);
        } catch (Exception e) {
            log.error("Failed to send WhatsApp message: {}", e.getMessage());
        }
    }
}

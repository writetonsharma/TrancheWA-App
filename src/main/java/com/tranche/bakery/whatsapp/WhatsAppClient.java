package com.tranche.bakery.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class WhatsAppClient {

    private final RestClient restClient;
    private final String phoneNumberId;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WhatsAppClient(
            @Value("${whatsapp.api.url}") String apiUrl,
            @Value("${whatsapp.api.token}") String token,
            @Value("${whatsapp.api.phone-number-id}") String phoneNumberId) {
        this.phoneNumberId = phoneNumberId;
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + token)
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

    public void sendImage(String to, String mediaId, String caption) {
        send(WhatsAppMessage.imageMessage(to, mediaId, caption));
    }

    public String uploadMedia(byte[] imageBytes, String filename) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("messaging_product", "whatsapp");
            body.add("type", "image/png");
            body.add("file", new ByteArrayResource(imageBytes) {
                @Override public String getFilename() { return filename; }
            });

            String response = restClient.post()
                    .uri("/{phoneNumberId}/media", phoneNumberId)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode json = objectMapper.readTree(response);
            return json.get("id").asText();
        } catch (Exception e) {
            log.error("Failed to upload media to WhatsApp: {}", e.getMessage());
            throw new RuntimeException("Media upload failed", e);
        }
    }

    private void send(Object message) {
        try {
            String response = restClient.post()
                    .uri("/{phoneNumberId}/messages", phoneNumberId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .body(String.class);
            log.debug("WhatsApp API response: {}", response);
        } catch (Exception e) {
            log.error("Failed to send WhatsApp message: {}", e.getMessage());
        }
    }
}


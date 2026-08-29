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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
@Slf4j
public class WhatsAppClient {

    private final RestClient restClient;
    private final String phoneNumberId;
    private final String templateLanguage;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WhatsAppClient(
            @Value("${whatsapp.api.url}") String apiUrl,
            @Value("${whatsapp.api.token}") String token,
            @Value("${whatsapp.api.phone-number-id}") String phoneNumberId,
            @Value("${whatsapp.template.language:en}") String templateLanguage) {
        this.phoneNumberId = phoneNumberId;
        this.templateLanguage = templateLanguage;
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    public SendOutcome sendText(String to, String body) {
        return send(to, WhatsAppMessage.text(to, body));
    }

    public SendOutcome sendButtons(String to, String bodyText, java.util.List<WhatsAppMessage.Button> buttons) {
        return send(to, WhatsAppMessage.buttonMessage(to, bodyText, buttons));
    }

    public SendOutcome sendList(String to, String bodyText, String buttonLabel, java.util.List<WhatsAppMessage.Section> sections) {
        return send(to, WhatsAppMessage.listMessage(to, bodyText, buttonLabel, sections));
    }

    public SendOutcome sendImage(String to, String mediaId, String caption) {
        return send(to, WhatsAppMessage.imageMessage(to, mediaId, caption));
    }

    public SendOutcome sendDocument(String to, String mediaId, String filename, String caption) {
        return send(to, WhatsAppMessage.documentMessage(to, mediaId, filename, caption));
    }

    /** Utility-template send (no header). */
    public SendOutcome sendTemplate(String to, String templateName, List<String> bodyParams) {
        return send(to, WhatsAppMessage.templateMessage(to, templateName, templateLanguage, bodyParams, null, null));
    }

    /** Utility-template send whose header carries a previously uploaded document (e.g. the receipt PDF). */
    public SendOutcome sendTemplateWithDocument(String to, String templateName, String mediaId,
                                                String filename, List<String> bodyParams) {
        return send(to, WhatsAppMessage.templateMessage(to, templateName, templateLanguage, bodyParams, mediaId, filename));
    }

    public String uploadMedia(byte[] imageBytes, String filename) {
        return uploadMedia(imageBytes, filename, "image/png");
    }

    public String uploadMedia(byte[] fileBytes, String filename, String mimeType) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("messaging_product", "whatsapp");
            body.add("type", mimeType);
            body.add("file", new ByteArrayResource(fileBytes) {
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

    private SendOutcome send(String to, Object message) {
        try {
            String response = restClient.post()
                    .uri("/{phoneNumberId}/messages", phoneNumberId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .body(String.class);
            log.info("WhatsApp message accepted to {}: {}", to, response);
            return SendOutcome.SENT;
        } catch (HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            if (isOutside24hWindow(responseBody)) {
                log.warn("WhatsApp send to {} blocked by the 24h window (131047) — will fall back to a template if one applies.", to);
                return SendOutcome.WINDOW_CLOSED;
            }
            log.error("WhatsApp send to {} FAILED: status={} response={} payload={}",
                    to, e.getStatusCode(), responseBody, payloadJson(message));
            return SendOutcome.FAILED;
        } catch (Exception e) {
            log.error("WhatsApp send to {} FAILED: {} payload={}", to, e.getMessage(), payloadJson(message));
            return SendOutcome.FAILED;
        }
    }

    // Serialise the outbound message (recipient + body/params) so a failure log shows exactly what didn't send.
    private String payloadJson(Object message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            return String.valueOf(message);
        }
    }

    // Error 131047 = business-initiated message outside the 24h window (re-engagement required).
    private boolean isOutside24hWindow(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return false;
        try {
            return objectMapper.readTree(responseBody).path("error").path("code").asInt() == 131047;
        } catch (Exception e) {
            return responseBody.contains("131047");
        }
    }
}


package com.tranche.bakery.whatsapp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WhatsAppMessage {

    // --- Outbound message builders ---

    public static TextMessage text(String to, String body) {
        return new TextMessage(to, new TextBody(body));
    }

    public static InteractiveMessage buttonMessage(String to, String bodyText, List<Button> buttons) {
        if (buttons != null) {
            for (Button button : buttons) {
                if (button.getReply() == null) continue;
                String title = button.getReply().getTitle();
                if (title != null && title.length() > MAX_BUTTON_TITLE_LEN) {
                    button.getReply().setTitle(title.substring(0, MAX_BUTTON_TITLE_LEN).trim());
                }
            }
        }
        var body = new InteractiveBody(bodyText);
        var action = new ButtonAction(buttons);
        return new InteractiveMessage(to, new Interactive("button", body, action));
    }

    // WhatsApp Cloud API rejects interactive-list row titles longer than 24
    // characters (error 131009). Enforce the cap here so no list send can 400,
    // regardless of the data source that built the rows.
    private static final int MAX_ROW_TITLE_LEN = 24;

    // Interactive reply-button titles are capped at 20 characters (error 131009).
    // Truncate defensively so an over-long title can never make the whole message
    // 400 and silently vanish (which previously dropped the order-status screen).
    private static final int MAX_BUTTON_TITLE_LEN = 20;

    public static InteractiveMessage listMessage(String to, String bodyText, String buttonLabel, List<Section> sections) {
        if (sections != null) {
            for (Section section : sections) {
                if (section.getRows() == null) continue;
                for (Row row : section.getRows()) {
                    String title = row.getTitle();
                    if (title != null && title.length() > MAX_ROW_TITLE_LEN) {
                        row.setTitle(title.substring(0, MAX_ROW_TITLE_LEN).trim());
                    }
                }
            }
        }
        var body = new InteractiveBody(bodyText);
        var action = new ListAction(buttonLabel, sections);
        return new InteractiveMessage(to, new Interactive("list", body, action));
    }

    // --- Message types ---

    @Data @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TextMessage {
        private String messaging_product = "whatsapp";
        private String to;
        private String type = "text";
        private TextBody text;

        TextMessage(String to, TextBody text) {
            this.to = to;
            this.text = text;
        }
    }

    @Data @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InteractiveMessage {
        private String messaging_product = "whatsapp";
        private String to;
        private String type = "interactive";
        private Interactive interactive;

        InteractiveMessage(String to, Interactive interactive) {
            this.to = to;
            this.interactive = interactive;
        }
    }

    // --- Nested payload types ---

    @Data @AllArgsConstructor
    public static class TextBody {
        private String body;
    }

    @Data @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Interactive {
        private String type;
        private InteractiveBody body;
        private Object action;
    }

    @Data @AllArgsConstructor
    public static class InteractiveBody {
        private String text;
    }

    @Data @AllArgsConstructor
    public static class ButtonAction {
        private List<Button> buttons;
    }

    @Data @AllArgsConstructor
    public static class ListAction {
        private String button;
        private List<Section> sections;
    }

    @Data @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Button {
        private String type = "reply";
        private ButtonReply reply;

        public Button(String id, String title) {
            this.reply = new ButtonReply(id, title);
        }
    }

    @Data @AllArgsConstructor
    public static class ButtonReply {
        private String id;
        private String title;
    }

    @Data @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Section {
        private String title;
        private List<Row> rows;
    }

    @Data @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Row {
        private String id;
        private String title;
        private String description;

        public Row(String id, String title) {
            this.id = id;
            this.title = title;
            this.description = null;
        }
    }

    @Data @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImageMessage {
        private String messaging_product = "whatsapp";
        private String to;
        private String type = "image";
        private ImagePayload image;

        ImageMessage(String to, ImagePayload image) {
            this.to = to;
            this.image = image;
        }
    }

    @Data @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImagePayload {
        private String id;
        private String caption;
    }

    public static ImageMessage imageMessage(String to, String mediaId, String caption) {
        return new ImageMessage(to, new ImagePayload(mediaId, caption));
    }

    @Data @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocumentMessage {
        private String messaging_product = "whatsapp";
        private String to;
        private String type = "document";
        private DocumentPayload document;

        DocumentMessage(String to, DocumentPayload document) {
            this.to = to;
            this.document = document;
        }
    }

    @Data @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocumentPayload {
        private String id;
        private String filename;
        private String caption;
    }

    public static DocumentMessage documentMessage(String to, String mediaId, String filename, String caption) {
        return new DocumentMessage(to, new DocumentPayload(mediaId, filename, caption));
    }
}

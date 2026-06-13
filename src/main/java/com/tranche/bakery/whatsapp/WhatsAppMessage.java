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
        var body = new InteractiveBody(bodyText);
        var action = new ButtonAction(buttons);
        return new InteractiveMessage(to, new Interactive("button", body, action));
    }

    public static InteractiveMessage listMessage(String to, String bodyText, String buttonLabel, List<Section> sections) {
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
}

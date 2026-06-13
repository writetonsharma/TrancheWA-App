package com.tranche.bakery.flow;

import lombok.Data;
import java.util.List;

@Data
public class MessageConfig {
    private String type;         // text | buttons | list
    private String body;
    private List<ButtonConfig> buttons;   // for type=buttons
    private String buttonLabel;           // for type=list
    private String dataSource;            // for type=list, dynamic from DB
}

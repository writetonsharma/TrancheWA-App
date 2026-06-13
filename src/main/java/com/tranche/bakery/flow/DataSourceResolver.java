package com.tranche.bakery.flow;

import com.tranche.bakery.whatsapp.WhatsAppMessage;
import java.util.List;
import java.util.Map;

public interface DataSourceResolver {
    List<WhatsAppMessage.Section> resolve(String dataSource, Map<String, Object> context);
}

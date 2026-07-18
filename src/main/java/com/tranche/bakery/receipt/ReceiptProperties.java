package com.tranche.bakery.receipt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bakery.receipt")
@Getter
@Setter
public class ReceiptProperties {
    private boolean enabled = true;
    private String businessName = "Tranche";
    private String tagline = "Artisan Bakery";
    private String contactPhone = "";
    private String location = "";
    private String fssai = "";
}

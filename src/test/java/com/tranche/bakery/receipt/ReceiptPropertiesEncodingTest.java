package com.tranche.bakery.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Guards the receipt identity against Spring's ISO-8859-1 .properties read mangling the UTF-8 accent. */
@SpringBootTest
@ActiveProfiles("test")
class ReceiptPropertiesEncodingTest {

    @Autowired
    private ReceiptProperties props;

    @Test
    void accentedIdentityLoadsWithoutMojibake() {
        assertThat(props.getBusinessName()).isEqualTo("Tranch\u00e9 Artisan Bakery");
        assertThat(props.getLocation()).contains("\u00b7");
    }
}

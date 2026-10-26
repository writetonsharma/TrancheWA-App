package com.tranche.bakery.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.tranche.bakery.whatsapp.SendOutcome;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class AuthenticationTemplateTest {
    @Test void actualOutboundJsonIsAuthenticationCopyCodeAndErrorsNeverLogSecrets(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://not-a-real-host.invalid");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WhatsAppClient client = new WhatsAppClient("https://not-a-real-host.invalid", "test-token", "12345", "en");
        ReflectionTestUtils.setField(client, "authenticationRestClient", builder.build());
        server.expect(requestTo("https://not-a-real-host.invalid/12345/messages")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                    {"messaging_product":"whatsapp","to":"919800000099","type":"template",
                    "template":{"name":"approved_login","language":{"code":"en"},"components":[
                    {"type":"body","parameters":[{"type":"text","text":"582391"}]},
                    {"type":"button","sub_type":"url","index":"0","parameters":[{"type":"text","text":"582391"}]}]}}
                    """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://not-a-real-host.invalid/12345/messages"))
                .andRespond(withBadRequest().body("582391 919800000099").contentType(MediaType.TEXT_PLAIN));
        assertThat(client.sendAuthenticationCode("919800000099", "approved_login", "en", "582391")).isEqualTo(SendOutcome.SENT);
        assertThat(client.sendAuthenticationCode("919800000099", "approved_login", "en", "582391")).isEqualTo(SendOutcome.FAILED);
        server.verify();
        assertThat(output.getAll()).doesNotContain("582391", "919800000099");
    }
    @Test void missingCredentialsCannotSend() {
        WhatsAppClient client = new WhatsAppClient("https://not-a-real-host.invalid", "", "", "en");
        assertThat(client.sendAuthenticationCode("919800000099", "approved_login", "en", "582391")).isEqualTo(SendOutcome.FAILED);
    }
}
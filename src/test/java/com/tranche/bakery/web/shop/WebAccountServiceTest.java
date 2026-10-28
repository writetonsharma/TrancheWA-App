package com.tranche.bakery.web.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.CapacityLock;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class WebAccountServiceTest {
    @Test void legacySuggestionBoundsAndControlsAreProjectedWithoutWrites() {
        // Include values PostgreSQL's current name bound/NUL restriction cannot store,
        // so the projection remains safe for any legacy Customer source as well.
        for (String[] legacy : List.of(
                new String[] {null, null, "", ""},
                new String[] {"  Shared\r\nName\tExample  ", "  Flat 42,\r\nAccount Lane\u2028Gurugram\u2029India  ",
                        "Shared Name Example", "Flat 42, Account Lane Gurugram India"},
                new String[] {"N".repeat(101), "A".repeat(2001), "", ""},
                new String[] {"N".repeat(100), "A".repeat(2000), "N".repeat(100), "A".repeat(2000)},
                new String[] {"Bad\u0000Name", "Flat 42\u0000Account Lane", "", ""},
                new String[] {"Bad\u007fName", "Flat 42\u0085Account Lane", "", ""},
                new String[] {" \r\n\t ", "short", "", ""})) {
            var jdbc = mock(JdbcTemplate.class);
            var carts = mock(WebCartService.class);
            var manager = mock(PlatformTransactionManager.class);
            var request = mock(HttpServletRequest.class);
            var customer = new Customer();
            customer.setId(7L);
            customer.setName(legacy[0]);
            customer.setDeliveryAddress(legacy[1]);
            when(carts.requireCustomer(request)).thenReturn(customer);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<WebAccountService.Profile>>any(), eq(7L)))
                    .thenReturn(List.of());
            var service = new WebAccountService(jdbc, new ObjectMapper(), carts,
                    mock(WebCheckoutService.class), mock(CapacityLock.class), manager);

            assertThat(service.profile(request)).isEqualTo(new WebAccountService.Profile(0, legacy[2], legacy[3], "", ""));
            assertThat(customer.getName()).isEqualTo(legacy[0]);
            assertThat(customer.getDeliveryAddress()).isEqualTo(legacy[1]);
            verify(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<WebAccountService.Profile>>any(), eq(7L));
            verify(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), eq(7L), eq(7L));
            verifyNoMoreInteractions(jdbc); // No profile INSERT/UPDATE or shared-customer write.
            verify(carts).requireCustomer(request);
            verifyNoMoreInteractions(carts);
        }
    }
}
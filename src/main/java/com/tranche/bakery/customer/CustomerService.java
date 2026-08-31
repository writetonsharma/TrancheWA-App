package com.tranche.bakery.customer;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    @Transactional
    public Customer findOrCreate(String phone) {
        Customer customer = customerRepository.findByPhone(phone)
                .orElseGet(() -> {
                    Customer c = new Customer();
                    c.setPhone(phone);
                    return c;
                });
        // Every inbound message re-anchors WhatsApp's 24h free-form window.
        customer.setLastInboundAt(LocalDateTime.now());
        return customerRepository.save(customer);
    }
}

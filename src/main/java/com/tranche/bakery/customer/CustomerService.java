package com.tranche.bakery.customer;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    @Transactional
    public Customer findOrCreate(String phone) {
        return customerRepository.findByPhone(phone)
                .orElseGet(() -> {
                    Customer c = new Customer();
                    c.setPhone(phone);
                    return customerRepository.save(c);
                });
    }
}

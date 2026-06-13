package com.tranche.bakery.feedback;

import com.tranche.bakery.customer.Customer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;

    @Transactional
    public void save(Customer customer, String message) {
        Feedback f = new Feedback();
        f.setCustomer(customer);
        f.setMessage(message);
        feedbackRepository.save(f);
    }
}

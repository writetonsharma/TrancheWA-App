package com.tranche.bakery.admin;

import com.tranche.bakery.customer.Customer;

import java.util.List;

public record ConversationThread(
        Customer customer,
        List<AdminMessage> messages
) {}

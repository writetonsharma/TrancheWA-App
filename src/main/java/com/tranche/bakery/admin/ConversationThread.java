package com.tranche.bakery.admin;

import java.util.List;

import com.tranche.bakery.customer.Customer;

public record ConversationThread(
        Customer customer,
        List<AdminMessage> messages,
        List<AdminOrderView> orders
) {}

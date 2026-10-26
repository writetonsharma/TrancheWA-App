/**
 * T002 / REQ-002,003,009 execution reference.
 * Scope verified against Customer (Long ID, digit-only WhatsApp phone, default marketing=true),
 * CustomerRepository, the existing admin chain and Cloud API transport; V45 was latest.
 * No endpoint probe scripts exist at the application root. No scenario skill root/task.md
 * was supplied. Decomposition: atomic identity boundary; commerce and frontend are separate workers.
 * Catalogue/quote must inject CustomerSessionService and call currentCustomer(request).
 * Only that helper resolves identity: Spring/admin Authentication is never customer identity.
 * No inbound CustomerService helper is called. Tests are authored, not executed in this worker.
 */
package com.tranche.bakery.web.auth;
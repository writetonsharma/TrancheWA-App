package com.tranche.bakery.order;

import java.sql.SQLException;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

import lombok.RequiredArgsConstructor;

/**
 * Cross-channel capacity mutex, released by PostgreSQL at transaction completion.
 * Acquire BEFORE customer/session, order, payment or subscription row locks/writes.
 * Never use an autocommit or a separate REQUIRES_NEW transaction for this lock.
 */
@Service
@RequiredArgsConstructor
public class CapacityLock {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException("Capacity lock requires an existing writable transaction");
        }
        // A flow may edit a draft and then enter pre-confirm in the SAME transaction. It must
        // never wait for capacity while retaining that draft row: fail/retry the whole flow instead.
        if (TransactionSynchronizationManager.getSynchronizations().stream().anyMatch(DraftRowLock.class::isInstance)) {
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(23734322354538565)", Boolean.class);
            if (!Boolean.TRUE.equals(acquired)) {
                throw new CannotAcquireLockException("Capacity changed during draft mutation; retry transaction");
            }
            return;
        }
        // One stable application-wide key. JdbcTemplate joins the caller's Spring-bound connection.
        String previousTimeout = jdbcTemplate.queryForObject("SHOW lock_timeout", String.class);
        jdbcTemplate.queryForObject("SELECT set_config('lock_timeout', '5s', true)", String.class);
        try {
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(23734322354538565)");
        } catch (DataAccessException ex) {
            throw translateLockFailure(ex);
        }
        // Do not change the caller's timeout for subsequent row locks. A timed-out transaction is
        // already aborted by PostgreSQL and must roll back; no SQL is attempted on that failure path.
        jdbcTemplate.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class,
            previousTimeout == null ? "0" : previousTimeout);
    }

    static DataAccessException translateLockFailure(DataAccessException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                return new CannotAcquireLockException("Capacity/order lock unavailable; retry transaction", ex);
            }
        }
        return ex;
    }

    void noteDraftRowLock() {
        if (TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(DraftRowLock.class::isInstance)) {
            TransactionSynchronizationManager.registerSynchronization(new DraftRowLock());
        }
    }

    private static final class DraftRowLock implements TransactionSynchronization { }
}
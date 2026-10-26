package com.tranche.bakery.order;

/** A rejected reservation must roll back its entire unit of work before the flow can recover. */
public class CapacityReservationException extends IllegalStateException {

    public CapacityReservationException(String code) {
        super(code);
    }
}
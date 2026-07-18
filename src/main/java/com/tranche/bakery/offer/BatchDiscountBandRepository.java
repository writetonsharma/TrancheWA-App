package com.tranche.bakery.offer;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchDiscountBandRepository extends JpaRepository<BatchDiscountBand, Long> {
    List<BatchDiscountBand> findAllByActiveTrueOrderByDisplayOrderAsc();
}

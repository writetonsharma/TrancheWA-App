package com.tranche.bakery.offer;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OfferRepository extends JpaRepository<Offer, Long> {
    List<Offer> findAllByOrderByDisplayOrderAsc();
    Optional<Offer> findByCode(String code);
}

package com.tranche.bakery.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class DeliveryAreaLoader {

    @Getter
    private List<DeliveryArea> areas = new ArrayList<>();

    @PostConstruct
    public void load() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new ClassPathResource("delivery-areas.json").getInputStream());
            areas = new ArrayList<>();
            for (JsonNode node : root.path("areas")) {
                List<String> pincodes = new ArrayList<>();
                for (JsonNode pin : node.path("pincodes")) {
                    pincodes.add(pin.asText());
                }
                areas.add(new DeliveryArea(
                        node.path("id").asText(),
                        node.path("name").asText(),
                        List.copyOf(pincodes)));
            }
            log.info("Loaded {} delivery areas", areas.size());
        } catch (IOException e) {
            log.error("Failed to load delivery-areas.json", e);
        }
    }

    /** Returns the area matching the given pincode, or null if not serviceable. */
    public DeliveryArea findByPincode(String pincode) {
        String normalized = pincode.trim();
        return areas.stream()
                .filter(a -> a.pincodes().contains(normalized))
                .findFirst()
                .orElse(null);
    }

    public String areaNamesSummary() {
        return areas.stream().map(DeliveryArea::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("select areas in Gurgaon");
    }

    public record DeliveryArea(String id, String name, List<String> pincodes) {}
}

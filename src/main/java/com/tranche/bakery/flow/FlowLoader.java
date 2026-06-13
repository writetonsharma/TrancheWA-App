package com.tranche.bakery.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FlowLoader {

    private final ObjectMapper objectMapper;
    private FlowConfig flowConfig;

    @PostConstruct
    public void load() throws Exception {
        var resource = new ClassPathResource("flow.json");
        flowConfig = objectMapper.readValue(resource.getInputStream(), FlowConfig.class);
        log.info("Flow config loaded: {} states", flowConfig.getStates().size());
    }

    public FlowConfig getConfig() {
        return flowConfig;
    }

    public StateConfig getState(String name) {
        return flowConfig.getStates().get(name);
    }
}

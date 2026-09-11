package com.example.demo.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Service
public class InvoiceAiClient {

    private static final Duration AI_TIMEOUT = Duration.ofMinutes(3);

    private final WebClient webClient;

    public InvoiceAiClient(
            @Value("${invoiceq.ai.base-url}") String baseUrl
    ) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public Mono<Map<String, Object>> health() {
        return webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(10));
    }

    public Mono<AiFailureExplanation> explain(AiExplainRequest request) {
        return webClient.post()
                .uri("/ai/explain")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiFailureExplanation.class)
                .timeout(AI_TIMEOUT);
    }

    public Flux<ServerSentEvent<String>> stream(AiExplainRequest request) {
        return webClient.post()
                .uri("/ai/explain/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .timeout(AI_TIMEOUT);
    }
}

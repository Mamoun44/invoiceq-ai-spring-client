package com.example.demo.ai;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/test-ai")
public class InvoiceAiController {

    private final InvoiceAiClient invoiceAiClient;

    public InvoiceAiController(InvoiceAiClient invoiceAiClient) {
        this.invoiceAiClient = invoiceAiClient;
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return invoiceAiClient.health();
    }

    @PostMapping("/explain")
    public Mono<AiFailureExplanation> explain(
            @Valid @RequestBody AiExplainRequest request
    ) {
        return invoiceAiClient.explain(request);
    }

    @PostMapping(value = "/explain/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @Valid @RequestBody AiExplainRequest request
    ) {
        return invoiceAiClient.stream(request);
    }
}

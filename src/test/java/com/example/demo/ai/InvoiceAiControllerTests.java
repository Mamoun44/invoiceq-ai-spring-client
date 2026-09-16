package com.example.demo.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(InvoiceAiController.class)
class InvoiceAiControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private InvoiceAiClient invoiceAiClient;

    @Test
    void forwardsStructuredExplanation() {
        var explanation = new AiFailureExplanation(
                "Invoice rejected",
                "The tax identifier is missing",
                List.of("supplier.taxId"),
                List.of("Provide the supplier tax identifier"),
                List.of("A supplier tax identifier is required"),
                "high",
                false
        );
        when(invoiceAiClient.explain(any())).thenReturn(Mono.just(explanation));

        webTestClient.post()
                .uri("/test-ai/explain")
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.likelyCause").isEqualTo("The tax identifier is missing")
                .jsonPath("$.affectedFields[0]").isEqualTo("supplier.taxId");
    }

    @Test
    void allowsGeneralQuestionsWithoutInvoiceContext() {
        when(invoiceAiClient.stream(any())).thenReturn(Flux.just(
                ServerSentEvent.<String>builder("{}").event("done").build()));
        webTestClient.post()
                .uri("/test-ai/explain/stream")
                .bodyValue(Map.of("question", "Which fields are required?"))
                .exchange()
                .expectStatus().isOk();
        org.mockito.Mockito.verify(invoiceAiClient).stream(
                org.mockito.ArgumentMatchers.argThat(request ->
                        request.invoice() == null && request.invoiceqError() == null));
    }
    @Test
    void forwardsSseEvents() {
        when(invoiceAiClient.stream(any())).thenReturn(Flux.just(
                ServerSentEvent.<String>builder("{\"text\":\"Hello\"}")
                        .event("token")
                        .build(),
                ServerSentEvent.<String>builder("{}")
                        .event("done")
                        .build()
        ));

        webTestClient.post()
                .uri("/test-ai/explain/stream")
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/event-stream")
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                        .contains("event:token", "data:{\"text\":\"Hello\"}", "event:done"));
    }

    private Map<String, Object> validRequest() {
        return Map.of(
                "invoice", Map.of("invoiceNumber", "INV-100"),
                "invoiceqError", Map.of("code", "TEST", "message", "Rejected"),
                "question", "Why did this invoice fail?"
        );
    }
}


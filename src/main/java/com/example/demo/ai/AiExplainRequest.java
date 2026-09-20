package com.example.demo.ai;


import jakarta.validation.constraints.Size;

import java.util.Map;

public record AiExplainRequest(
        Map<String, Object> invoice,
        Map<String, Object> invoiceqError,
        @Size(max = 2000) String question
) {
    public AiExplainRequest {
        if (question == null || question.isBlank()) {
            question = "Why did this invoice fail?";
        }
    }
}


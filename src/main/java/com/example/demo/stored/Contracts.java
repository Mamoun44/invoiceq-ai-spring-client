package com.example.demo.stored;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class Contracts {
    private Contracts() {}
    public record User(UUID id, UUID corporationId, String email, String role) {}
    public record Login(@NotBlank @Email @Size(max=254) String email,
                        @NotBlank @Size(max=72) String password) {}
    public record Session(String accessToken, String tokenType, long expiresIn, User user) {}
    public record NewUser(@NotBlank @Email @Size(max=254) String email,
                          @NotBlank @Size(min=12,max=72) String password,
                          @NotNull Role role) {}
    public enum Role { ADMIN, VIEWER }
    public enum Status { CLEARED, UNCLEARED, PENDING }
    public enum AmountType { totalIncludingTax, remainingPayable }
    public enum StatusScope { all, selected }
    public record AmountQuery(@NotBlank @Size(max=200) String invoiceNumber,
                              @NotNull AmountType amountType, List<Status> statuses) {}
    public record TotalsQuery(@NotNull AmountType amountType, @NotNull StatusScope statusScope,
                              @Size(max=30) List<Status> statuses,
                              @Pattern(regexp="[A-Z]{3}") String currency,
                              LocalDate dateFrom, LocalDate dateTo) {
        public TotalsQuery {
            if (amountType == null) amountType = AmountType.remainingPayable;
        }
    }
    public record IntegrationStatusUpdate(@NotNull Status status) {}
    public record NewInvoice(@NotBlank @Size(max=200) String invoiceNumber,
                             @NotNull @Digits(integer=17,fraction=7) BigDecimal totalIncludingTax,
                             @NotNull @Digits(integer=17,fraction=7) BigDecimal remainingPayable,
                             @NotNull @Pattern(regexp="[A-Z]{3}") String currency,
                             @NotNull Status status, @NotNull LocalDate issueDate,
                             @Digits(integer=17,fraction=7) BigDecimal totalExcludingTax) {}
    public record AmountRow(String currency, String amount, long invoiceCount,
                            String totalExcludingTax, String totalIncludingTax,
                            String remainingPayable, long missingPreTaxCount) {}
    public record Data(String corporationId, String status, String invoiceNumber,
                       String amountType, List<AmountRow> totals) {}
    public record Question(@NotBlank @Size(max=2000) String question) {}
}


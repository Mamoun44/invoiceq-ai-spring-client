package com.example.demo.stored;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import static com.example.demo.stored.Contracts.*;

@RestController
public class StoredInvoiceController {
    private final AccountService accounts;
    private final InvoiceRepository invoices;
    private final WebClient python;
    public StoredInvoiceController(AccountService accounts, InvoiceRepository invoices,
                                   @Value("${invoiceq.ai.base-url}") String pythonUrl) {
        this.accounts=accounts;this.invoices=invoices;
        this.python=WebClient.builder().baseUrl(pythonUrl).build();
    }
    private <T> Mono<T> blocking(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }
    private User user(Authentication auth) { return (User) auth.getPrincipal(); }

    @PostMapping("/auth/login")
    public Mono<ResponseEntity<Session>> login(@Valid @RequestBody Login request) {
        return blocking(()->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.login(request)));
    }
    @GetMapping("/auth/me")
    public User me(Authentication auth) { return user(auth); }

    @PostMapping("/auth/logout")
    public Mono<ResponseEntity<Void>> logout(Authentication auth) {
        return blocking(()-> { accounts.logout(auth.getCredentials().toString());return ResponseEntity.noContent().build(); });
    }
    @PostMapping("/api/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<User> createUser(Authentication auth,@Valid @RequestBody NewUser request) {
        return blocking(()->accounts.createUser(user(auth).corporationId(),request));
    }
    @PostMapping("/api/admin/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String,String>> createInvoice(Authentication auth,@Valid @RequestBody NewInvoice request) {
        return blocking(()->invoices.create(user(auth),request));
    }
    @PatchMapping("/api/admin/invoices/{invoiceId}/integration-status")
    public Mono<ResponseEntity<Void>> updateStatus(Authentication auth, @PathVariable UUID invoiceId,
                                                   @Valid @RequestBody IntegrationStatusUpdate request) {
        return blocking(()-> { invoices.updateIntegrationStatus(user(auth),invoiceId,request.status());
            return ResponseEntity.noContent().build(); });
    }
    @GetMapping("/internal/invoice-assistant/context")
    public Map<String,Object> context(Authentication auth) {
        return Map.of("corporationId",user(auth).corporationId().toString(),
            "allowedTools",List.of("get_invoice_amount","get_invoice_totals"),
            "allowedStatuses",Arrays.stream(Status.values()).map(Enum::name).toList());
    }
    @PostMapping("/internal/invoice-assistant/amount")
    public Mono<Data> amount(Authentication auth,@Valid @RequestBody AmountQuery request) {
        return blocking(()->invoices.amount(user(auth),request));
    }
    @PostMapping("/internal/invoice-assistant/totals")
    public Mono<Data> totals(Authentication auth,@Valid @RequestBody TotalsQuery request) {
        return blocking(()->invoices.totals(user(auth),request));
    }
    @PostMapping("/api/invoices/ask")
    public Mono<ResponseEntity<String>> ask(Authentication auth,@Valid @RequestBody Question request) {
        return python.post().uri("/ai/invoices/query")
            .headers(headers->headers.setBearerAuth(auth.getCredentials().toString()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
            .exchangeToMono(response->response.bodyToMono(String.class).defaultIfEmpty("{}")
                .map(body->ResponseEntity.status(response.statusCode()).contentType(MediaType.APPLICATION_JSON)
                    .cacheControl(CacheControl.noStore()).body(body)))
            .timeout(Duration.ofMinutes(3))
            .onErrorMap(error->new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invoice assistant unavailable"));
    }
}


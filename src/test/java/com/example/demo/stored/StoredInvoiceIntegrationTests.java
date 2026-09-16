package com.example.demo.stored;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.http.MediaType;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static com.example.demo.stored.Contracts.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoredInvoiceIntegrationTests {
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate db;
    @Autowired Environment environment;
    WebTestClient client;
    String adminA, adminB, viewer;
    UUID companyA;
    static final String PASSWORD="Test-password-123!";

    @BeforeEach void setup() {
        client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+environment.getProperty("local.server.port")).build();
        db.update("DELETE FROM auth_sessions");
        db.update("DELETE FROM invoices");
        db.update("DELETE FROM app_users");
        db.update("DELETE FROM companies");
        companyA=accounts.bootstrap("A","Company A","a@example.test",PASSWORD);
        accounts.bootstrap("B","Company B","b@example.test",PASSWORD);
        accounts.createUser(companyA,new NewUser("viewer@example.test",PASSWORD,Role.VIEWER));
        adminA=login("a@example.test");adminB=login("b@example.test");viewer=login("viewer@example.test");
    }
    String login(String email) {
        return client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email",email,"password",PASSWORD)).exchange().expectStatus().isOk()
            .expectHeader().valueMatches("Cache-Control",".*no-store.*")
            .expectBody(Session.class).returnResult().getResponseBody().accessToken();
    }
    void create(String token,String number,String amount,String currency,String status) {
        client.post().uri("/api/admin/invoices").headers(h->h.setBearerAuth(token))
            .bodyValue(Map.of("invoiceNumber",number,"totalIncludingTax",amount,"remainingPayable",amount,
                "currency",currency,"status",status,"issueDate","2026-09-15"))
            .exchange().expectStatus().isCreated();
    }
    @Test void isolatesSameNumberAndAggregatesByCurrencyAndStatus() {
        create(adminA,"001","105.25","AED","CLEARED");
        create(adminB,"001","999","AED","CLEARED");
        create(adminA,"002","20.10","USD","CLEARED");
        create(adminA,"003","200","AED","PENDING");
        client.post().uri("/internal/invoice-assistant/amount").headers(h->h.setBearerAuth(viewer))
            .bodyValue(Map.of("invoiceNumber","001","amountType","totalIncludingTax","statuses",List.of()))
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.corporationId").isEqualTo(companyA.toString())
            .jsonPath("$.totals[0].amount").isEqualTo("105.2500000");
        client.post().uri("/internal/invoice-assistant/totals").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("amountType","totalIncludingTax","statusScope","selected","statuses",List.of("CLEARED")))
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.totals.length()").isEqualTo(2)
            .jsonPath("$.totals[0].amount").isEqualTo("105.2500000")
            .jsonPath("$.totals[0].invoiceCount").isEqualTo(1)
            .jsonPath("$.totals[1].currency").isEqualTo("USD");
    }
    @Test void rejectsInvalidTokensAndViewerWrites() {
        client.get().uri("/internal/invoice-assistant/context").exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/invoice-assistant/context").headers(h->h.setBearerAuth("fake")).exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/admin/users").headers(h->h.setBearerAuth(viewer))
            .bodyValue(Map.of("email","x@example.test","password",PASSWORD,"role","ADMIN"))
            .exchange().expectStatus().isForbidden();
        client.post().uri("/auth/logout").headers(h->h.setBearerAuth(adminA)).exchange().expectStatus().isNoContent();
        client.get().uri("/auth/me").headers(h->h.setBearerAuth(adminA)).exchange().expectStatus().isUnauthorized();
        db.update("UPDATE auth_sessions SET expires_at=?",Timestamp.from(Instant.now().minusSeconds(1)));
        client.get().uri("/auth/me").headers(h->h.setBearerAuth(adminB)).exchange().expectStatus().isUnauthorized();
    }
    @Test void doesNotLeakOtherCompanyOrAcceptCompanyOverride() {
        create(adminB,"private","77","AED","CLEARED");
        client.post().uri("/internal/invoice-assistant/amount").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("invoiceNumber","private","amountType","totalIncludingTax"))
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.status").isEqualTo("not_found").jsonPath("$.totals.length()").isEqualTo(0);
        client.post().uri("/internal/invoice-assistant/amount").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("invoiceNumber","private","amountType","totalIncludingTax","corporationId","B"))
            .exchange().expectStatus().isBadRequest();
    }
    @Test void requiresExplicitFiltersAndUsesParameterizedLookup() {
        create(adminA,"001","105","AED","CLEARED");
        client.post().uri("/internal/invoice-assistant/totals").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("amountType","totalIncludingTax"))
            .exchange().expectStatus().isBadRequest();
        client.post().uri("/internal/invoice-assistant/totals").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("amountType","totalIncludingTax","statusScope","selected","statuses",List.of("MADE_UP")))
            .exchange().expectStatus().isBadRequest();
        client.post().uri("/internal/invoice-assistant/amount").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("invoiceNumber","' OR 1=1 --","amountType","totalIncludingTax"))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("not_found");
        client.post().uri("/internal/invoice-assistant/totals").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("amountType","totalIncludingTax","statusScope","all","currency","USD"))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("no_matches");
    }
    @Test void storesOnlyHashesAndLocksFailedLogins() {
        String passwordHash=db.queryForObject("SELECT password_hash FROM app_users WHERE email=?",String.class,"a@example.test");
        assertThat(passwordHash).doesNotContain(PASSWORD).startsWith("$2");
        assertThat(db.queryForList("SELECT token_hash FROM auth_sessions",String.class)).doesNotContain(adminA,adminB,viewer);
        for (int i=0;i<5;i++) {
            client.post().uri("/auth/login").bodyValue(Map.of("email","a@example.test","password","incorrect"))
                .exchange().expectStatus().isUnauthorized();
        }
        client.post().uri("/auth/login").bodyValue(Map.of("email","a@example.test","password",PASSWORD))
            .exchange().expectStatus().isUnauthorized();
    }

    @Test void totalsReturnAllThreeAmountsAndVerifiedStatuses() {
        client.post().uri("/api/admin/invoices").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("invoiceNumber","paid-part","totalExcludingTax","100.00",
                "totalIncludingTax","105.00","remainingPayable","55.00",
                "currency","AED","status","UNCLEARED","issueDate","2026-09-15"))
            .exchange().expectStatus().isCreated();
        client.post().uri("/internal/invoice-assistant/totals").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("statusScope","all"))
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.amountType").isEqualTo("remainingPayable")
            .jsonPath("$.totals[0].amount").isEqualTo("55.0000000")
            .jsonPath("$.totals[0].totalExcludingTax").isEqualTo("100.0000000")
            .jsonPath("$.totals[0].totalIncludingTax").isEqualTo("105.0000000")
            .jsonPath("$.totals[0].remainingPayable").isEqualTo("55.0000000");
        client.get().uri("/internal/invoice-assistant/context").headers(h->h.setBearerAuth(adminA))
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.allowedStatuses[0]").isEqualTo("CLEARED")
            .jsonPath("$.allowedStatuses[1]").isEqualTo("UNCLEARED")
            .jsonPath("$.allowedStatuses[2]").isEqualTo("PENDING");
        client.post().uri("/api/admin/invoices").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("invoiceNumber","bad","totalIncludingTax","105","remainingPayable","55",
                "currency","AED","status","REJECTED","issueDate","2026-09-15"))
            .exchange().expectStatus().isBadRequest();
        create(adminA,"missing-tax","20","AED","CLEARED");
        client.post().uri("/internal/invoice-assistant/totals").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("statusScope","all")).exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.totals[0].totalExcludingTax").isEmpty()
            .jsonPath("$.totals[0].missingPreTaxCount").isEqualTo(1)
            .jsonPath("$.totals[0].totalIncludingTax").isEqualTo("125.0000000")
            .jsonPath("$.totals[0].remainingPayable").isEqualTo("75.0000000");
    }
    @Test void requiresReviewForLegacyStatusesAndScopesStatusUpdates() {
        UUID invoiceId=UUID.randomUUID();
        db.update("INSERT INTO invoices(id,company_id,invoice_number,total_including_tax,remaining_payable,currency,legacy_status,issue_date) VALUES (?,?,?,?,?,?,?,?)",
            invoiceId,companyA,"legacy","105","55","AED","REJECTED",java.time.LocalDate.of(2026,9,15));
        client.post().uri("/internal/invoice-assistant/totals").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("statusScope","all")).exchange().expectStatus().isEqualTo(409);
        client.patch().uri("/api/admin/invoices/"+invoiceId+"/integration-status").headers(h->h.setBearerAuth(adminB))
            .bodyValue(Map.of("status","UNCLEARED")).exchange().expectStatus().isNotFound();
        client.patch().uri("/api/admin/invoices/"+invoiceId+"/integration-status").headers(h->h.setBearerAuth(viewer))
            .bodyValue(Map.of("status","UNCLEARED")).exchange().expectStatus().isForbidden();
        client.patch().uri("/api/admin/invoices/"+invoiceId+"/integration-status").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("status","UNCLEARED")).exchange().expectStatus().isNoContent();
        client.post().uri("/internal/invoice-assistant/totals").headers(h->h.setBearerAuth(adminA))
            .bodyValue(Map.of("statusScope","all")).exchange().expectStatus().isOk();
    }
}


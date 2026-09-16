# Authenticated invoice backend

## What is implemented

Spring Security protects the stored-invoice and admin endpoints. Login issues a
cryptographically random, one-hour bearer session. Only SHA-256 token hashes
are stored in PostgreSQL. Passwords use BCrypt cost 12. Logout revokes the session;
a new login revokes the user's previous sessions. Five failed logins lock the
account for 15 minutes. No default users/passwords are installed.

Initial scope: one company per user account, ADMIN and VIEWER roles. Company
switching and multi-company memberships are not implemented. Both roles can
read company invoice amounts; only ADMIN can add users or invoices. New users
always inherit the administrator's company; bodies cannot choose company IDs.
Company provisioning is an explicit local setup operation.

Invoices contain invoice number, amount including tax, remaining payable,
currency, status and issue date. These are stored amounts, not an integration
that automatically synchronizes with InvoiceQ or calculates payment balances.
Integration statuses are CLEARED, UNCLEARED, PENDING, as documented by InvoiceQ UAE Swagger. Credit notes are not included.
Amounts are decimals with up to 7 fractional digits. Issue dates are calendar
dates. Numbers are unique per company. Invoice creation records supplied data;
it does not perform the full anonymous invoice validation workflow.

## PostgreSQL configuration

Database: invoiceAi, host localhost, port 5432, user postgres.
Flyway creates only a dedicated invoice_ai schema and its tables.
No existing public-schema tables are migrated or dropped. Flyway clean is disabled.
Configuration uses DB_URL, DB_USERNAME and DB_PASSWORD; no password is committed.

The local helper prompts for the PostgreSQL password without echoing or saving
it. Do not pass passwords in command-line arguments or commit them.

### 1. Create your first company/admin

From PowerShell in this project:

```powershell
.\run-local.ps1 -Bootstrap
```

Enter your real PostgreSQL password, company code/name, admin email and a new
administrator password. This applies migrations, creates the company/admin
atomically, then stops. Run it again with a different company code and email for
the second company. Duplicate codes or emails fail without changing existing data.
No fake invoices are inserted.

### 2. Start Spring

```powershell
.\run-local.ps1
```

Leave the terminal running. The service listens on port 8080.

### 3. Start Python and Angular

In the Python project's .env, INVOICE_BACKEND_URL must be
http://127.0.0.1:8080. Restart Python on port 8001.
Run npm start in its frontend directory; the existing proxy routes to Spring.
Sign in using the administrator email/password from step 1. Tokens are kept
only in page memory; refreshing requires login again.

## API operations

- POST /auth/login: email, password -> accessToken, tokenType, expiresIn, user.
- GET /auth/me: verified account and company.
- POST /auth/logout: revoke the current token.
- POST /api/admin/users: email, password, role (ADMIN or VIEWER).
- POST /api/admin/invoices: create an invoice for the authenticated company.
- POST /api/invoices/ask: question -> Python tool result and explanation.
- GET /internal/invoice-assistant/context: verified company and permissions.
- POST /internal/invoice-assistant/amount: invoiceNumber, amountType.
- POST /internal/invoice-assistant/totals: amountType, statusScope, statuses,
  optional currency/dateFrom/dateTo.

Every operation other than login and the existing /test-ai anonymous routes
requires Authorization: Bearer <accessToken>. The internal routes independently
verify the token; their names do not substitute for authentication.

Use totalIncludingTax or remainingPayable for amountType. For totals, omitting amountType defaults to remainingPayable; the response always includes all three amounts. Totals require
statusScope all with an empty statuses list, or selected with a nonempty list.
Unknown statuses, amount types and extra JSON properties are rejected. Date
bounds are inclusive. Results are grouped by currency; the LLM never sums rows.
An invoice in another company looks identical to an absent invoice.

Example invoice body for POST /api/admin/invoices (use your own data):

```json
{
  "invoiceNumber": "001",
  "totalExcludingTax": "100.00",
  "totalIncludingTax": "105.00",
  "remainingPayable": "55.00",
  "currency": "AED",
  "status": "CLEARED",
  "issueDate": "2026-09-15"
}
```

The browser provides login and invoice questions. User/invoice administration
currently uses these authenticated APIs (e.g. Postman); no admin dashboard exists.

## Tests and deployment boundary

mvnw.cmd test runs an isolated H2 database in PostgreSQL compatibility mode.
Integration tests use real HTTP, security filters, migrations and SQL queries,
with two companies sharing invoice number 001. No Gemini calls are made and no
tests connect to invoiceAi. PostgreSQL startup still needs to be verified locally
with your password; H2 compatibility tests are not a substitute for that check.

Before internet deployment, configure TLS, a least-privilege database account
instead of postgres, reverse-proxy rate limiting (including unknown-account login
attempts), appropriate network restrictions, monitoring and backups. Current
sessions are bearer-header only: cookie/basic auth is disabled, so CSRF is disabled
for these APIs. Do not add cookie authentication without adding CSRF protection.
Password recovery, MFA, account invitation and company switching are not yet built.

Security references:
- https://docs.spring.io/spring-security/reference/reactive/configuration/webflux.html
- https://docs.spring.io/spring-security/reference/7.1/api/java/org/springframework/security/web/server/authentication/AuthenticationWebFilter.html


## V2 integration statuses and totals overview

Restart Spring to apply V2; do not edit or rerun V1 manually. The original status
is preserved as legacy_status. CLEARED transfers directly. Other old values are
not presumed equivalent to UNCLEARED or PENDING. Review these rows using:

    SELECT id, invoice_number, company_id, legacy_status
    FROM invoice_ai.invoices WHERE status IS NULL;

After verifying the actual integration status, the company's ADMIN can call
PATCH /api/admin/invoices/{id}/integration-status with {"status":"UNCLEARED"}
(or the actual verified CLEARED/PENDING value). This requires the admin bearer
token and rejects cross-company updates. All-status totals involving unresolved
rows return 409; queries for specific integration statuses remain available.

Totals report, per currency:
- totalExcludingTax: complete sum before tax; null if any value is missing.
- totalIncludingTax: complete sum including tax.
- remainingPayable: complete sum still owed, including tax after recorded payments.
- missingPreTaxCount: number of records missing the before-tax value.

Existing invoices have no stored before-tax amount. Enter the real value rather
than assuming a VAT rate. Do not rerun bootstrap or recreate the database.
New invoice JSON may include totalExcludingTax; it remains optional for compatibility.
Legacy records can be backfilled in the database from verified source documents.

Verified status source: https://sandbox.invoiceq.com/swagger-ui/uae/specs/full.json

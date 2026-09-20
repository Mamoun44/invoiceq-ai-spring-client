CREATE TABLE companies (
 id UUID PRIMARY KEY,
 code VARCHAR(80) NOT NULL UNIQUE,
 name VARCHAR(200) NOT NULL
);
CREATE TABLE app_users (
 id UUID PRIMARY KEY,
 company_id UUID NOT NULL REFERENCES companies(id),
 email VARCHAR(254) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','VIEWER')),
 active BOOLEAN NOT NULL DEFAULT TRUE,
 failed_logins INTEGER NOT NULL DEFAULT 0,
 locked_until TIMESTAMP WITH TIME ZONE
);
CREATE TABLE auth_sessions (
 token_hash VARCHAR(64) PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_users(id),
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX session_expiry_idx ON auth_sessions(expires_at);
CREATE TABLE invoices (
 id UUID PRIMARY KEY,
 company_id UUID NOT NULL REFERENCES companies(id),
 invoice_number VARCHAR(200) NOT NULL,
 total_including_tax NUMERIC(24,7) NOT NULL,
 remaining_payable NUMERIC(24,7) NOT NULL,
 currency VARCHAR(3) NOT NULL,
 status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','CLEARED','REJECTED','CANCELLED')),
 issue_date DATE NOT NULL,
 UNIQUE(company_id, invoice_number)
);
CREATE INDEX invoice_totals_idx ON invoices(company_id, status, issue_date, currency);


-- Preserve legacy statuses without inventing integration-status mappings.
ALTER TABLE invoices RENAME COLUMN status TO legacy_status;
ALTER TABLE invoices ALTER COLUMN legacy_status DROP NOT NULL;
ALTER TABLE invoices ADD COLUMN status VARCHAR(20);
ALTER TABLE invoices ADD CONSTRAINT invoices_integration_status_check
 CHECK (status IN ('CLEARED', 'UNCLEARED', 'PENDING'));
UPDATE invoices SET status = 'CLEARED' WHERE legacy_status = 'CLEARED';
ALTER TABLE invoices ADD CONSTRAINT invoices_status_or_legacy_check
 CHECK (status IS NOT NULL OR legacy_status IS NOT NULL);
ALTER TABLE invoices ADD COLUMN total_excluding_tax NUMERIC(24,7);
CREATE INDEX invoice_integration_totals_idx ON invoices(company_id, status, issue_date, currency);

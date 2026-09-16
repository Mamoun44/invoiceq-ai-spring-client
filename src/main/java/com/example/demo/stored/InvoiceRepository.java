package com.example.demo.stored;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static com.example.demo.stored.Contracts.*;

@Repository
public class InvoiceRepository {
    private final JdbcTemplate db;
    public InvoiceRepository(JdbcTemplate db) { this.db=db; }
    private String column(AmountType type) {
        if (type == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Amount type required");
        return switch(type) { case totalIncludingTax -> "total_including_tax"; case remainingPayable -> "remaining_payable"; };
    }
    public Data amount(User user, AmountQuery query) {
        if (query.statuses()!=null && !query.statuses().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Status filters do not apply to individual lookup");
        String column=column(query.amountType());
        var rows=db.query("SELECT currency,"+column+" AS amount,total_excluding_tax,total_including_tax,remaining_payable FROM invoices WHERE company_id=? AND invoice_number=?",
            (r,n)->new AmountRow(r.getString("currency"),r.getBigDecimal("amount").toPlainString(),1,
                r.getBigDecimal("total_excluding_tax")==null?null:r.getBigDecimal("total_excluding_tax").toPlainString(),
                r.getBigDecimal("total_including_tax").toPlainString(),r.getBigDecimal("remaining_payable").toPlainString(),
                r.getBigDecimal("total_excluding_tax")==null?1:0),
            user.corporationId(),query.invoiceNumber());
        return new Data(user.corporationId().toString(),rows.isEmpty()?"not_found":"ok",
            query.invoiceNumber(),query.amountType().name(),rows);
    }
    public Data totals(User user, TotalsQuery query) {
        var statuses=query.statuses()==null?List.<Status>of():query.statuses();
        if (query.statusScope()==null || (query.statusScope()==StatusScope.selected && statuses.isEmpty())
            || (query.statusScope()==StatusScope.all && !statuses.isEmpty())
            || (query.dateFrom()!=null && query.dateTo()!=null && query.dateFrom().isAfter(query.dateTo())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Explicit, consistent status scope and date filters are required");
        StringBuilder where=new StringBuilder(" FROM invoices WHERE company_id=?");
        List<Object> args=new ArrayList<>(); args.add(user.corporationId());
        if (!statuses.isEmpty()) {
            where.append(" AND status IN (").append(String.join(",",Collections.nCopies(statuses.size(),"?"))).append(")");
            statuses.forEach(s->args.add(s.name()));
        }
        if (query.currency()!=null) { where.append(" AND currency=?");args.add(query.currency()); }
        if (query.dateFrom()!=null) { where.append(" AND issue_date>=?");args.add(query.dateFrom()); }
        if (query.dateTo()!=null) { where.append(" AND issue_date<=?");args.add(query.dateTo()); }
        if (query.statusScope()==StatusScope.all) {
            Long unresolved=db.queryForObject("SELECT COUNT(*)"+where+" AND status IS NULL",Long.class,args.toArray());
            if (unresolved!=null && unresolved>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Verify legacy integration statuses before requesting all-status totals");
        }
        String sql="SELECT currency,SUM("+column(query.amountType())+") AS amount,COUNT(*) AS count,"
            +"SUM(total_excluding_tax) AS pretax,SUM(total_including_tax) AS gross,"
            +"SUM(remaining_payable) AS payable,COUNT(*)-COUNT(total_excluding_tax) AS missing_pretax"
            +where+" GROUP BY currency ORDER BY currency";
        var rows=db.query(sql,(r,n)->new AmountRow(r.getString("currency"),
            r.getBigDecimal("amount").toPlainString(),r.getLong("count"),
            r.getLong("missing_pretax")>0?null:r.getBigDecimal("pretax").toPlainString(),
            r.getBigDecimal("gross").toPlainString(),r.getBigDecimal("payable").toPlainString(),
            r.getLong("missing_pretax")),args.toArray());
        return new Data(user.corporationId().toString(),rows.isEmpty()?"no_matches":"ok",
            null,query.amountType().name(),rows);
    }
    public void updateIntegrationStatus(User user, UUID invoiceId, Status status) {
        int updated=db.update("UPDATE invoices SET status=? WHERE id=? AND company_id=?",
            status.name(),invoiceId,user.corporationId());
        if(updated==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Invoice not found");
    }
    public Map<String,String> create(User user, NewInvoice request) {
        UUID id=UUID.randomUUID();
        db.update("""
            INSERT INTO invoices(id,company_id,invoice_number,total_including_tax,remaining_payable,currency,status,issue_date,total_excluding_tax)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,id,user.corporationId(),request.invoiceNumber(),request.totalIncludingTax(),
            request.remainingPayable(),request.currency(),request.status().name(),request.issueDate(),request.totalExcludingTax());
        return Map.of("id",id.toString(),"invoiceNumber",request.invoiceNumber());
    }
}

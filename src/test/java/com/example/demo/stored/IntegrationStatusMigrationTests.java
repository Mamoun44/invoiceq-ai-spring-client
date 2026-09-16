package com.example.demo.stored;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class IntegrationStatusMigrationTests {
    @Test void preservesLegacyValuesAndNeverGuessesPretax() throws Exception {
        String url="jdbc:h2:mem:migration-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        try (var pool = new HikariDataSource()) {
        pool.setJdbcUrl(url); pool.setUsername("sa"); pool.setPassword(""); pool.setMaximumPoolSize(2);
        Flyway.configure().dataSource(pool).schemas("invoice_ai").defaultSchema("invoice_ai").target("1").load().migrate();
        UUID company=UUID.randomUUID();
        try(var connection=pool.getConnection()) {
            connection.setSchema("invoice_ai");
            try(var insert=connection.prepareStatement("INSERT INTO companies(id,code,name) VALUES (?,?,?)")) {
                insert.setObject(1,company);insert.setString(2,"test");insert.setString(3,"Test");insert.executeUpdate();
            }
            for (String status:new String[]{"CLEARED","REJECTED","DRAFT","CANCELLED"}) {
                try(var insert=connection.prepareStatement("INSERT INTO invoices(id,company_id,invoice_number,total_including_tax,remaining_payable,currency,status,issue_date) VALUES (?,?,?,105,55,'AED',?,DATE '2026-09-15')")) {
                    insert.setObject(1,UUID.randomUUID());insert.setObject(2,company);insert.setString(3,status);insert.setString(4,status);insert.executeUpdate();
                }
            }
        }
        Flyway.configure().dataSource(pool).schemas("invoice_ai").defaultSchema("invoice_ai").load().migrate();
        try(var connection=pool.getConnection()) {
            connection.setSchema("invoice_ai");
            try(var query=connection.createStatement();var rows=query.executeQuery("SELECT legacy_status,status,total_excluding_tax,total_including_tax,remaining_payable FROM invoices")) {
                int count=0;
                while(rows.next()) {
                    count++;
                    assertThat(rows.getString("status")).isEqualTo(rows.getString("legacy_status").equals("CLEARED")?"CLEARED":null);
                    assertThat(rows.getBigDecimal("total_excluding_tax")).isNull();
                    assertThat(rows.getBigDecimal("total_including_tax")).isEqualByComparingTo("105");
                    assertThat(rows.getBigDecimal("remaining_payable")).isEqualByComparingTo("55");
                }
                assertThat(count).isEqualTo(4);
            }
        }
    }
}


}


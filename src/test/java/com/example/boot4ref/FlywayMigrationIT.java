package com.example.boot4ref;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRunAllMigrationsSuccessfully() {
        var applied = flyway.info().applied();
        assertThat(applied).hasSize(4);
    }

    // --- Products table ---

    @Test
    void shouldCreateProductsTable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_name = 'products'
                ORDER BY ordinal_position
                """);

        assertThat(columns).isNotEmpty();
        assertThat(columnNames(columns))
                .contains("id", "name", "price", "stock", "status", "version", "created_at", "updated_at");

        assertColumn(columns, "id", "bigint");
        assertColumn(columns, "name", "character varying");
        assertColumn(columns, "price", "numeric");
        assertColumn(columns, "stock", "integer");
        assertColumn(columns, "status", "character varying");
        assertColumn(columns, "version", "bigint");
        assertColumn(columns, "created_at", "timestamp with time zone");
        assertColumn(columns, "updated_at", "timestamp with time zone");
    }

    // --- Orders + order_lines tables ---

    @Test
    void shouldCreateOrdersTable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_name = 'orders'
                ORDER BY ordinal_position
                """);

        assertThat(columns).isNotEmpty();
        assertThat(columnNames(columns))
                .contains("id", "status", "total_amount", "idempotency_key", "version", "created_at", "updated_at");
    }

    @Test
    void shouldCreateOrderLinesTable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_name = 'order_lines'
                ORDER BY ordinal_position
                """);

        assertThat(columns).isNotEmpty();
        assertThat(columnNames(columns))
                .contains("id", "order_id", "product_id", "quantity", "unit_price", "created_at", "updated_at");

        // Verify foreign keys exist
        List<Map<String, Object>> fks = jdbcTemplate.queryForList("""
                SELECT tc.constraint_name, kcu.column_name, ccu.table_name AS foreign_table_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = 'order_lines'
                """);

        assertThat(fks).hasSizeGreaterThanOrEqualTo(2);
    }

    // --- Outbox table ---

    @Test
    void shouldCreateOutboxEventsTable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_name = 'outbox_events'
                ORDER BY ordinal_position
                """);

        assertThat(columns).isNotEmpty();
        assertThat(columnNames(columns))
                .contains("id", "aggregate_type", "aggregate_id", "event_type", "payload",
                        "status", "created_at", "processed_at", "retry_count", "version");

        // Verify payload is JSONB
        assertColumn(columns, "payload", "jsonb");
    }

    // --- Indexes ---

    @Test
    void shouldCreateIndexes() {
        List<String> indexNames = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
                """, String.class);

        // V4 drops idx_products_created_at_id and idx_orders_created_at_id (TSID ordering)
        assertThat(indexNames).contains(
                "idx_products_status",
                "idx_orders_status",
                "idx_order_lines_order_id",
                "idx_order_lines_product_id",
                "idx_outbox_pending",
                "idx_outbox_aggregate"
        );
        assertThat(indexNames).doesNotContain(
                "idx_products_created_at_id",
                "idx_orders_created_at_id"
        );
    }

    // --- Helpers ---

    private List<String> columnNames(List<Map<String, Object>> columns) {
        return columns.stream()
                .map(row -> (String) row.get("column_name"))
                .toList();
    }

    private void assertColumn(List<Map<String, Object>> columns, String name, String expectedType) {
        var column = columns.stream()
                .filter(row -> name.equals(row.get("column_name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Column not found: " + name));
        assertThat(column.get("data_type")).isEqualTo(expectedType);
    }
}

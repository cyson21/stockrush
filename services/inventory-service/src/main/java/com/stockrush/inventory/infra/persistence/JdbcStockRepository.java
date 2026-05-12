package com.stockrush.inventory.infra.persistence;

import com.stockrush.inventory.application.StockRepository;
import com.stockrush.inventory.application.StockSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStockRepository implements StockRepository {

    private final JdbcClient jdbcClient;

    JdbcStockRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<StockSnapshot> findAll() {
        return jdbcClient.sql("""
                select sku_id, product_code, available_quantity, reserved_quantity, version
                from stock_items
                order by sku_id
                """)
            .query(this::mapStock)
            .list();
    }

    @Override
    public List<StockSnapshot> findByProductCode(String productCode) {
        return jdbcClient.sql("""
                select sku_id, product_code, available_quantity, reserved_quantity, version
                from stock_items
                where product_code = :productCode
                order by sku_id
                """)
            .param("productCode", productCode)
            .query(this::mapStock)
            .list();
    }

    @Override
    public Optional<StockSnapshot> findBySkuId(String skuId) {
        return jdbcClient.sql("""
                select sku_id, product_code, available_quantity, reserved_quantity, version
                from stock_items
                where sku_id = :skuId
                """)
            .param("skuId", skuId)
            .query(this::mapStock)
            .optional();
    }

    @Override
    public StockSnapshot setAvailableQuantity(String skuId, String productCode, int availableQuantity) {
        return jdbcClient.sql("""
                insert into stock_items (
                  sku_id, product_code, available_quantity, reserved_quantity, version, created_at, updated_at
                )
                values (:skuId, :productCode, :availableQuantity, 0, 0, now(), now())
                on conflict (sku_id) do update
                set product_code = excluded.product_code,
                    available_quantity = excluded.available_quantity,
                    version = stock_items.version + 1,
                    updated_at = now()
                returning sku_id, product_code, available_quantity, reserved_quantity, version
                """)
            .param("skuId", skuId)
            .param("productCode", productCode)
            .param("availableQuantity", availableQuantity)
            .query(this::mapStock)
            .single();
    }

    private StockSnapshot mapStock(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StockSnapshot(
            resultSet.getString("sku_id"),
            resultSet.getString("product_code"),
            resultSet.getInt("available_quantity"),
            resultSet.getInt("reserved_quantity"),
            resultSet.getLong("version")
        );
    }
}

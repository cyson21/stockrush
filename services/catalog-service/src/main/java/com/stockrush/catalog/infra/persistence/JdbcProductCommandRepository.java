package com.stockrush.catalog.infra.persistence;

import com.stockrush.catalog.application.ProductCommandRepository;
import com.stockrush.catalog.application.ProductSnapshot;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProductCommandRepository implements ProductCommandRepository {

    private final JdbcClient jdbcClient;

    JdbcProductCommandRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public ProductSnapshot create(ProductSnapshot product) {
        return jdbcClient.sql("""
                insert into products (product_code, name, sales_status, list_price, created_at, updated_at)
                values (:productCode, :name, :salesStatus, :listPrice, now(), now())
                returning product_code, name, sales_status, list_price
                """)
            .param("productCode", product.productCode())
            .param("name", product.name())
            .param("salesStatus", product.status())
            .param("listPrice", product.listPrice())
            .query(this::mapProduct)
            .single();
    }

    @Override
    public Optional<ProductSnapshot> update(String productCode, String name, String salesStatus, BigDecimal listPrice) {
        return jdbcClient.sql("""
                update products
                set name = :name, sales_status = :salesStatus, list_price = :listPrice, updated_at = now()
                where product_code = :productCode
                returning product_code, name, sales_status, list_price
                """)
            .param("productCode", productCode)
            .param("name", name)
            .param("salesStatus", salesStatus)
            .param("listPrice", listPrice)
            .query(this::mapProduct)
            .optional();
    }

    private ProductSnapshot mapProduct(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProductSnapshot(
            resultSet.getString("product_code"),
            resultSet.getString("name"),
            resultSet.getString("sales_status"),
            resultSet.getBigDecimal("list_price")
        );
    }
}

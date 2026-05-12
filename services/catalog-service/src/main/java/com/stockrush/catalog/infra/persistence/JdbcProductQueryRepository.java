package com.stockrush.catalog.infra.persistence;

import com.stockrush.catalog.application.ProductQueryRepository;
import com.stockrush.catalog.application.ProductSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProductQueryRepository implements ProductQueryRepository {

    private final JdbcClient jdbcClient;

    JdbcProductQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<ProductSnapshot> findByStatus(String status) {
        return jdbcClient.sql("""
                select product_code, name, sales_status, list_price
                from products
                where sales_status = :status
                order by product_code
                """)
            .param("status", status)
            .query(this::mapProduct)
            .list();
    }

    @Override
    public Optional<ProductSnapshot> findByProductCode(String productCode) {
        return jdbcClient.sql("""
                select product_code, name, sales_status, list_price
                from products
                where product_code = :productCode
                """)
            .param("productCode", productCode)
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

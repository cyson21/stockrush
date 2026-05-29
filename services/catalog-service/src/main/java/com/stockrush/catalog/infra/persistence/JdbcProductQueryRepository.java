// JdbcProductQueryRepository: 영속성 계층 접근을 캡슐화해 데이터 조회·저장 의도를 분리합니다.

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
    public List<ProductSnapshot> findByStatus(String status, String query) {
        StringBuilder sql = new StringBuilder("""
                select product_code, name, sales_status, list_price
                from products
                where sales_status = :status
                """);
        if (query != null) {
            sql.append(" and (")
                .append("lower(product_code) like :query escape '\\'")
                .append(" or ")
                .append("lower(name) like :query escape '\\'")
                .append(")");
        }
        sql.append(" order by product_code");

        var queryBuilder = jdbcClient.sql(sql.toString())
            .param("status", status);

        if (query != null) {
            queryBuilder.param("query", "%" + escapeLikePattern(query.toLowerCase()) + "%");
        }

        return queryBuilder.query(this::mapProduct).list();
    }

    private String escapeLikePattern(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
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

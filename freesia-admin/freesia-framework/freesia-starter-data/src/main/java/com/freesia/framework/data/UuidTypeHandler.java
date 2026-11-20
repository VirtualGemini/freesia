package com.freesia.framework.data;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Binds native PostgreSQL UUIDs and reads database-generated keys without string conversion. */
@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public final class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, UUID value, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, value);
    }

    @Override
    public UUID getNullableResult(ResultSet result, String column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet result, int column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int column) throws SQLException {
        return statement.getObject(column, UUID.class);
    }
}

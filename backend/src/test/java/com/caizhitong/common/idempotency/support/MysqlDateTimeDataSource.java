package com.caizhitong.common.idempotency.support;

import java.lang.reflect.*;
import java.sql.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Real H2 connections, exposing Connector/J's default untyped DATETIME result shape. */
public final class MysqlDateTimeDataSource extends DelegatingDataSource {
    public MysqlDateTimeDataSource(DataSource source) { super(source); }
    @Override public Connection getConnection() throws SQLException { return wrap(Connection.class, super.getConnection()); }
    private <T> T wrap(Class<T> type, T target) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Object result;
            try { result = method.invoke(target, args); }
            catch (InvocationTargetException e) { throw e.getCause(); }
            if (result instanceof PreparedStatement statement) return wrap(PreparedStatement.class, statement);
            if (result instanceof ResultSet rows) return wrap(ResultSet.class, rows);
            if (target instanceof ResultSet rows && "getObject".equals(method.getName()) && args.length == 1 && result instanceof Timestamp value) {
                String column = args[0] instanceof Integer index ? rows.getMetaData().getColumnLabel(index) : args[0].toString();
                if ("expires_at".equalsIgnoreCase(column)) return value.toLocalDateTime();
            }
            return result;
        }));
    }
}

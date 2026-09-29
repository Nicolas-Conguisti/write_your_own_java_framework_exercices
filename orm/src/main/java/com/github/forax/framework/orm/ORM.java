package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ORM {
  private ORM() {
    throw new AssertionError();
  }

  @FunctionalInterface
  public interface TransactionBlock {
    void run() throws SQLException;
  }

  private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
      int.class, "INTEGER",
      Integer.class, "INTEGER",
      long.class, "BIGINT",
      Long.class, "BIGINT",
      String.class, "VARCHAR(255)"
  );

  private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) {
    var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
        .flatMap(superInterface -> {
          if (superInterface instanceof ParameterizedType parameterizedType
              && parameterizedType.getRawType() == Repository.class) {
            return Stream.of(parameterizedType);
          }
          return null;
        })
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
    var typeArgument = repositorySupertype.getActualTypeArguments()[0];
    if (typeArgument instanceof Class<?> beanType) {
      return beanType;
    }
    throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
  }

  private static class UncheckedSQLException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 42L;

    private UncheckedSQLException(SQLException cause) {
      super(cause);
    }

    @Override
    public SQLException getCause() {
      return (SQLException) super.getCause();
    }
  }

  private static final ThreadLocal<Connection> CURRENT_CONNECTION = new ThreadLocal<>();

  static Connection currentConnection(){
    if(CURRENT_CONNECTION.get() == null){
     throw new IllegalStateException("No current connection");
    }
    return CURRENT_CONNECTION.get();
  }

  public static void transaction(DataSource dataSource, TransactionBlock block) throws SQLException {
    Objects.requireNonNull(dataSource);
    Objects.requireNonNull(block);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      CURRENT_CONNECTION.set(connection);
      try {
        block.run();
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        CURRENT_CONNECTION.remove();
      }
    }
  }

  static String findTableName(Class<?> beanClass){
    var annotation = beanClass.getAnnotation(Table.class);
    var tableName = annotation != null ? annotation.value() : beanClass.getSimpleName();
    return tableName.toUpperCase(Locale.ROOT);
  }

  static String findColumnName(PropertyDescriptor property){
    var getter = property.getReadMethod();
    var name = property.getName();
    if(getter == null){
      return name;
    }
    var column = getter.getAnnotation(Column.class);
    return column == null ? name : column.value();
  }

  private static final String findSQLType(Class<?> type){
    var SQLType = TYPE_MAPPING.getOrDefault(type, "VARCHAR(255");
    return SQLType + (type.isPrimitive() ? " NOT NULL" : "");
  }

  private static String generatedValue(PropertyDescriptor property){
    var getter = property.getReadMethod();
    return getter.isAnnotationPresent(GeneratedValue.class)
            ? " AUTO_INCREMENT"
            : "";
  }

  private static String identity(PropertyDescriptor property, String columnName){
    var getter = property.getReadMethod();
    return getter.isAnnotationPresent(Id.class)
            ? ",\nPRIMARY KEY (" + findColumnName(property) + ")"
            : "";
  }

  public static void createTable(Class<?> beanClass) throws SQLException {
    Objects.requireNonNull(beanClass);

    var connection = currentConnection();

    var table = findTableName(beanClass);
    var bean = Utils.beanInfo(beanClass);

    var query = "CREATE TABLE " + table + " " +
            Arrays.stream(bean.getPropertyDescriptors())
                    .filter(property -> !property.getName().equals("class"))
                    .filter(property -> property.getReadMethod() != null)
                    .map(property -> {
                      var columnName = findColumnName(property);
                      return columnName + " " + findSQLType(property.getPropertyType())
                      + generatedValue(property)
                      + identity(property, columnName);
                    })
                    .collect(Collectors.joining(",\n", "(", ")"));

    try(Statement statement = connection.createStatement()) {
      statement.executeUpdate(query);
    }
    connection.commit();
  }

  public static <T extends Repository<?, ?>> T createRepository(Class<T> repositoryType){
    Objects.requireNonNull(repositoryType);
    return repositoryType.cast(Proxy.newProxyInstance(
            repositoryType.getClassLoader(),
            new Class<?>[] {repositoryType},
            (Object _, Method method, Object[] args) -> {
              var connection = currentConnection();
              if(method.getDeclaringClass() == Object.class) {
                throw new UnsupportedOperationException();
              }
              return switch (method.getName()){
                case "findAll" -> {
                  yield List.of(); // TODO
                }
                  default -> throw new IllegalStateException("Unknown method " + method.getName());
              };
            }));
  }

  /*private Class<?> toEntityClass(DataSource resultSet, BeanInfo beanInfo, Constructor<?> constructor){
    var instance = Utils.newInstance(constructor);

  }


  private static Object findAll(Connection connection, String query, BeanInfo bean, Constructor<?> constructor){

  }*/
}

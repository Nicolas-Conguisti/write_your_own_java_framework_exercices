package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.FeatureDescriptor;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
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
      } catch (UncheckedSQLException e) {
        connection.rollback();
        throw e.getCause();
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
    var beanType = findBeanTypeFromRepository(repositoryType);
    var beanInfo = Utils.beanInfo(beanType);
    var propertyId = findId(beanInfo);
    var constructor = Utils.defaultConstructor(beanType);
    var tableName = findTableName(beanType);
    return repositoryType.cast(Proxy.newProxyInstance(
            repositoryType.getClassLoader(),
            new Class<?>[] {repositoryType},
            (Object _, Method method, Object[] args) -> {
              var connection = currentConnection();
              if(method.getDeclaringClass() == Object.class) {
                throw new UnsupportedOperationException();
              }
              try{
                return switch (method.getName()){
                  case "findAll" -> {
                    var sqlQuery = "SELECT * FROM " + tableName;
                    yield findAll(connection, sqlQuery, beanInfo, constructor);
                  }
                  case "save" -> {
                    var bean = args[0];
                    save(connection, tableName, beanInfo, bean, propertyId);
                    yield bean;
                  }
                  case "findById" -> {
                    var id = args[0];
                    var sqlQuery = "SELECT * FROM " + tableName + " WHERE " + propertyId.getName() + " = ?";
                    yield findById(connection, sqlQuery, beanInfo, constructor, id);
                  }
                  default -> throw new IllegalStateException("Unknown method " + method.getName());
                };
              }
              catch (SQLException e){
                throw new UncheckedSQLException(e);
              }
            }));
  }

  static Object toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<?> constructor) throws SQLException {
    var instance = Utils.newInstance(constructor);
    var properties = beanInfo.getPropertyDescriptors();
    var column = 1;
    for(var property : properties){
      if(property.getName().equals("class")){
        continue;
      }
      var setter = property.getWriteMethod();
      var argument = resultSet.getObject(column++);
      Utils.invokeMethod(instance, setter, argument);
    }
    return instance;
  }

  static List<Object> findAll(Connection connection, String sqlQuery, BeanInfo beanInfo, Constructor<?> constructor, Object...args) throws SQLException {
    var list = new ArrayList<>();
    try(PreparedStatement statement = connection.prepareStatement(sqlQuery)) {
      //statement.setObject(1, "James Bond"); pas de requête paramétrique
      for(var i = 0; i<args.length; i++){
        statement.setObject(1+i, args[i]);
      }
      try(var resultSet = statement.executeQuery()) {
        while(resultSet.next()) {
          var instance = toEntityClass(resultSet, beanInfo, constructor);
          list.add(instance);
        }
      }
    }
    return list;
  }

  // String sqlQuery = """
  //  INSERT INTO FOO (id, name) VALUES (?, ?);
  //""";
  static String createSaveQuery(String tableName, BeanInfo beanInfo){
    var properties = beanInfo.getPropertyDescriptors();
    return "MERGE INTO " + tableName + " " + // MERGE = INSERT + UPDATE :))))
      Arrays.stream(properties)
              .map(FeatureDescriptor::getName)
              .filter(propertyName -> !propertyName.equals("class"))
              .collect(Collectors.joining(", ", "(", ")"))
            + " VALUES (" +
            String.join(", ", Collections.nCopies(properties.length - 1, "?"))
            + ");";
  }

  static void save(Connection connection, String tableName, BeanInfo beanInfo, Object bean, PropertyDescriptor idProperty) throws SQLException {
    var sqlQuery = createSaveQuery(tableName, beanInfo);
    var column = 1;
    var generateKeys = idProperty == null ? Statement.NO_GENERATED_KEYS : Statement.RETURN_GENERATED_KEYS;
    try(PreparedStatement statement = connection.prepareStatement(sqlQuery, generateKeys)) {
      for(var property : beanInfo.getPropertyDescriptors()){
        if(property.getName().equals("class")){
          continue;
        }
        var getter = property.getReadMethod();
        var value = Utils.invokeMethod(bean, getter);
        statement.setObject(column++, value);
      }
      statement.executeUpdate();
      try(ResultSet resultSet = statement.getGeneratedKeys()) {
        if (resultSet.next()) {
          var key = resultSet.getObject(1);
          var setter = idProperty.getWriteMethod();
          Utils.invokeMethod(bean, setter, key);
        }
      }
    }
  }

  static PropertyDescriptor findId(BeanInfo beanInfo){
    return Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> property.getReadMethod().isAnnotationPresent(Id.class))
            .findFirst()
            .orElse(null);
  }

  static Object findById(Connection connection, String sqlQuery, BeanInfo beanInfo, Constructor<?> constructor, Object id) throws SQLException {
    findAll(connection, sqlQuery, beanInfo, constructor, id);
    return null;
  }
}

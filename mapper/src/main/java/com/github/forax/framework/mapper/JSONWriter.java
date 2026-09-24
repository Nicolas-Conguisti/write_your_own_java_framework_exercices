package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class JSONWriter {

    private final HashMap<Class<?>, Generator> map;
    private static final GeneratorCache CACHE = new GeneratorCache();

    public JSONWriter() {
        map = new HashMap<>();
        super();
    }

    /**
    * Works only with JSON primitive values, null, true, false, any integers or doubles and strings
    */
    public String toJSON(Object o) {

        return switch(o){
            case null -> "null";
            case Boolean _, Double _, Integer _ -> String.valueOf(o); // Or o + "" (idiomatic method)
            case String s -> "\"" + s + "\"";
            case Object obj -> {
                var type = obj.getClass();
                var generator = map.get(type);
                if(generator == null){
                    generator = CACHE.get(obj.getClass());
                }
                yield generator.generate(this, obj);
            }
        };
    }

    public <T> void configure(Class<T> type, Function<? super T, String> function) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(function);

        var previous = map.putIfAbsent(type, (_, object) -> function.apply(type.cast(object)));
        if (previous != null) {
            throw new IllegalStateException("Type already configured: " + type.getName());
        }
    }

    static class GeneratorCache extends ClassValue<Generator> {

        @Override
        protected Generator computeValue(Class<?> type) {

            List<?> properties;
            if (type.isRecord()) {
                properties = recordProperties(type);
            }
            else{
                properties = beanProperties(type);
            }

            var generators = properties.stream()
                    .<Generator>map(property -> {
                        String name;
                        Method getter;
                        switch(property){
                            case RecordComponent r -> {
                                name = r.getName();
                                getter = r.getAccessor();
                            }
                            case PropertyDescriptor p -> {
                                name = p.getName();
                                getter = p.getReadMethod();
                            }
                            default -> throw new IllegalArgumentException("Type is not a bean and not a record !");
                        }

                        var jsonProperty = getter.getAnnotation(JSONProperty.class);
                        if(jsonProperty != null){
                            name = jsonProperty.value();
                        }
                        var prefixe = "\"" + name + "\": ";
                        return (writer, bean) -> {
                            var value = Utils.invokeMethod(bean, getter);
                            return prefixe + writer.toJSON(value);
                        };
                    })
                    .toList();

            return (writer, bean) -> generators.stream()
                    .map(generator -> generator.generate(writer, bean))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
    }

    @FunctionalInterface
    private interface Generator {
        String generate(JSONWriter writer, Object bean);
    }

    private static List<PropertyDescriptor> beanProperties(Class<?> type) {
        var beanInfo = Utils.beanInfo(type);
        var properties = beanInfo.getPropertyDescriptors();
        return Arrays.stream(properties)
                .flatMap(property -> {
                    if(property == null
                        || property.getName().equals("class")
                        || property.getReadMethod() == null){
                        return null;
                    }
                    return Stream.of(property);
                })
                .toList();
    }

    private static List<RecordComponent> recordProperties(Class<?> type) {
        var rawComponents = type.getRecordComponents();
        return Arrays.stream(rawComponents)
                .filter(Objects::nonNull)
                .toList();
    }
}
package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
            case Boolean _, Double _, Integer _ -> o + "";
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
            var beanInfo = Utils.beanInfo(type);
            var generators = Arrays.stream(beanInfo.getPropertyDescriptors())
                    .filter(property -> !property.getName().equals("class"))
                    .filter(property -> property.getReadMethod() != null)
                    .<Generator>map(property -> {
                        var name = property.getName();
                        var getter = property.getReadMethod();

                        var jsonProperty = getter.getAnnotation(JSONProperty.class);
                        if (jsonProperty != null) {
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
}
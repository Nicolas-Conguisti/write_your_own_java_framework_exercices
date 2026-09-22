package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class InjectorRegistry {

    private final HashMap<Class<?>, Supplier<?>> instances;

    public InjectorRegistry(){
        instances = new HashMap<>();
        super();
    }

    public <T> void registerInstance(Class<T> type, T instance){
        Objects.requireNonNull(type);
        Objects.requireNonNull(instance);
        registerProvider(type, () -> instance);
    }

    // Sous-types of T are accepted
    public <T> void registerProvider(Class<T> type, Supplier<? extends T> supplier){
        Objects.requireNonNull(type);
        Objects.requireNonNull(supplier);
        var result = instances.putIfAbsent(type, supplier);
        if(result != null){
            throw new IllegalStateException("Type already registered " + type.getName());
        }
    }

    private static Constructor<?> findConstructor(Class<?> providerClass){
        var constructors = providerClass.getConstructors();
        var constructorsList = Arrays.stream(constructors)
                .filter(constructor -> constructor.isAnnotationPresent(Inject.class))
                .toArray(Constructor<?>[]::new);

        return switch(constructorsList.length){
            case 0 -> Utils.defaultConstructor(providerClass);
            case 1 -> constructorsList[0];
            default -> throw new IllegalStateException("Class has many inject constructors");
        };
    }

    public <T> void registerProviderClass(Class<T> type, Class<? extends T> providerClass){
        Objects.requireNonNull(type);
        Objects.requireNonNull(providerClass);

        var constructor = findConstructor(providerClass);
        var properties = findInjectableProperties(type);

        registerProvider(type, () -> {
            var args = Arrays.stream(constructor.getParameterTypes())
                    .map(this::lookupInstance)
                    .toArray();
            var instance = Utils.newInstance(constructor, args);
            for (var property : properties){
                var setter = property.getWriteMethod();
                var value = lookupInstance(property.getPropertyType()); // Ask the type of the property
                Utils.invokeMethod(instance, setter, value);
            }
            return type.cast(instance);
        });
    }

    public void registerProviderClass(Class<?> serviceClass) {
        Objects.requireNonNull(serviceClass);
        registerProviderClassInternal(serviceClass);
    }

    private <T> void registerProviderClassInternal(Class<T> serviceClass) {
        Objects.requireNonNull(serviceClass);
        registerProviderClass(serviceClass, serviceClass);
    }

    public <T> T lookupInstance(Class<T> type){
        Objects.requireNonNull(type);
        var supplier = instances.get(type);
        if(supplier == null){
            throw new IllegalStateException("Instance not registered for type " + type.getName());
        }
        return type.cast(supplier.get());
    }

    static List<PropertyDescriptor> findInjectableProperties(Class<?> type){
        var beanInfo = Utils.beanInfo(type);
        var properties = beanInfo.getPropertyDescriptors();
        return Arrays.stream(properties)
                .filter(property -> {
                    var setter = property.getWriteMethod();
                    return setter != null && setter.isAnnotationPresent(Inject.class);
                }).toList();
    }
}
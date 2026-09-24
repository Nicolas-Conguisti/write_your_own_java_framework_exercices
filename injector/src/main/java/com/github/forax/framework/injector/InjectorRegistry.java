package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class InjectorRegistry {

    // Map of instances : For one class, we have a supplier that return an instance of this class.
    private final HashMap<Class<?>, Supplier<?>> instances;

    public InjectorRegistry(){
        instances = new HashMap<>();
        super();
    }

    /**
     * Method that create an instance of a required class. The returned type is necessary an object of the class (same type)
     * We use type.cast() to cast the Object returned by supplier.get() to the same type as the class type in parameter.
     * In the declaration, <T'> means that all the T types are the very sames, whatever it is.
     */
    public <T> T lookupInstance(Class<T> type){
        Objects.requireNonNull(type);
        var supplier = instances.get(type);
        if(supplier == null){
            throw new IllegalStateException("Instance not registered for type " + type.getName());
        }
        return type.cast(supplier.get());
    }

    /**
     * Register into "instances" a type T and his implementation directly.
     * In the declaration, <T'> means that all the T types are the very sames, whatever it is.
     */
    public <T> void registerInstance(Class<T> type, T instance){
        Objects.requireNonNull(type);
        Objects.requireNonNull(instance);
        registerProvider(type, () -> instance);
    }

    /**
     * Register into "instances" a type T and his implementation, based on a supplier that returns it.
     * In the declaration, <T'> means that all the T types are the very sames, whatever it is.
     * T or subtypes of T are accepted for the supplier.
     * Throws an exception if the type already has an implementation in "instances"
     */
    public <T> void registerProvider(Class<T> type, Supplier<? extends T> supplier){
        Objects.requireNonNull(type);
        Objects.requireNonNull(supplier);
        var result = instances.putIfAbsent(type, supplier);
        if(result != null){
            throw new IllegalStateException("Type already registered " + type.getName());
        }
    }

    /**
     * Register into "instances" a type T and his implementation, based on an entire class (T or a class that extends T).
     * In the declaration, <T'> means that all the T types are the very sames, whatever it is.
     * providerClass is a class that has to be constructed. For this, we use the injectables properties of the type T.
     * Calls registerProvider with a supplier that constructs the instance of providerClass.
     * Take into account if the constructor have arguments and create each (args)
     * Take into account the injectables properties of the type T.
     * We use type.cast() to cast the Object returned by supplier.get() to the same type as the class type in parameter.
     */
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

    /**
     * Public method to register class and instance, witch have the very same type
     * Does not contain the <'T> annotation (user-friendly)
     */
    public void registerProviderClass(Class<?> serviceClass) {
        Objects.requireNonNull(serviceClass);
        registerProviderClassInternal(serviceClass);
    }

    /**
     * Private method that contains the <'T> annotation (not user-friendly)
     */
    private <T> void registerProviderClassInternal(Class<T> serviceClass) {
        Objects.requireNonNull(serviceClass);
        registerProviderClass(serviceClass, serviceClass);
    }

    /**
     * Find injectables properties of a class
     * An injectable property is a property that has an annotated @Inject setter
     * Use the reflexion API on the Bean of the class
     */
    static List<PropertyDescriptor> findInjectableProperties(Class<?> type){
        var beanInfo = Utils.beanInfo(type);
        var properties = beanInfo.getPropertyDescriptors();
        return Arrays.stream(properties)
                .filter(property -> {
                    var setter = property.getWriteMethod();
                    return setter != null && setter.isAnnotationPresent(Inject.class);
                }).toList();
    }

    /**
     * Find a valid constructor in a class.
     * A valid constructor is a constructor annotated @Inject or the default constructor
     * If we have many @Inject constructor, returns an error
     * If we have no @Inject constructor, returns the default constructor
     */
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
}
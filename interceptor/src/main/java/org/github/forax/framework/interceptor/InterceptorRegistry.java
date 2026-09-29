package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class InterceptorRegistry {
  private final HashMap<Class<? extends Annotation>, List<Interceptor>> registryMap;
  private final HashMap<Method, Invocation> cache;

  public InterceptorRegistry(){
      registryMap = new HashMap<>();
      cache = new HashMap<>();
      super();
  }

  public void addAroundAdvice(Class<? extends Annotation> annotationClass, AroundAdvice aroundAdvice){
    Objects.requireNonNull(annotationClass);
    Objects.requireNonNull(aroundAdvice);
    addInterceptor(annotationClass, (instance, method, args, invocation) -> {
        aroundAdvice.before(instance, method, args);
        var result = invocation.proceed(instance, method, args);
        aroundAdvice.after(instance, method, args, result);
        return result;
    });
  }

  public void addInterceptor(Class<? extends Annotation> annotationClass, Interceptor interceptor){
      Objects.requireNonNull(annotationClass);
       Objects.requireNonNull(interceptor);
       registryMap.computeIfAbsent(annotationClass, _ -> new ArrayList<>())
               .add(interceptor);
       cache.clear();
  }

  /*List<AroundAdvice> findAdvices(Method method){
      return Arrays.stream(method.getAnnotations())
              .flatMap(annotation -> registryMap.getOrDefault(annotation.annotationType(), List.of()).stream())
              .toList();
  }*/

    List<Interceptor> findInterceptors(Method method){

        var classAnnotations = method.getDeclaringClass().getAnnotations();
        var methodAnnotations = method.getDeclaredAnnotations();
        var methodParameterAnnotations = method.getParameterAnnotations();

        return Stream.of(
                Arrays.stream(classAnnotations),
                Arrays.stream(methodAnnotations),
                Arrays.stream(methodParameterAnnotations).flatMap(Arrays::stream))
                .flatMap(s -> s)
                .distinct()
            .flatMap(annotation -> registryMap.getOrDefault(annotation.annotationType(), List.of()).stream())
            .toList();
    }

    static Invocation getInvocation(List<Interceptor> interceptors){
        /*Invocation invocation = (instance, method, args) -> {
            return Utils::invokeMethod(instance, method, args);
        }*/
        Invocation invocation = Utils::invokeMethod;
        for(var interceptor : interceptors.reversed()){
            var oldInvocation = invocation;
            invocation = (instance, method, args) -> interceptor.intercept(instance, method, args, oldInvocation);
        }
        return invocation;
    }

  public <T> T createProxy(Class<T> type, T delegate){
    Objects.requireNonNull(type);
    Objects.requireNonNull(delegate);
    return type.cast(Proxy.newProxyInstance(
        type.getClassLoader(),
        new Class<?>[] {type},
        (Object proxy, Method method, Object[] args) -> {
            var invocation = cache.computeIfAbsent(method, m -> {
                var interceptors = findInterceptors(m); // Don't capture "method" !! Use m instead of Method
                return getInvocation(interceptors);
            });
            return invocation.proceed(delegate, method, args);
        }
    ));
  }
}

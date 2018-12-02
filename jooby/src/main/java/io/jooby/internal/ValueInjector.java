package io.jooby.internal;

import io.jooby.Err;
import io.jooby.Reified;
import io.jooby.StatusCode;
import io.jooby.Value;
import org.jooby.funzy.Throwing;

import javax.inject.Inject;
import javax.inject.Named;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class ValueInjector {

  private static final String AMBIGUOUS_CONSTRUCTOR =
      "Ambiguous constructor found. Expecting a single constructor or only one annotated with "
          + Inject.class.getName();
  private boolean missingToNull;

  public ValueInjector missingToNull() {
    missingToNull = true;
    return this;
  }

  public <T> T inject(Reified<T> type, Value scope) {
    try {
      Object result = value(scope, type.getRawType(), type.getType());
      return (T) result;
    } catch (InstantiationException | IllegalAccessException x) {
      throw Throwing.sneakyThrow(x);
    } catch (InvocationTargetException x) {
      throw Throwing.sneakyThrow(x.getCause());
    }
  }

  private <T> T newInstance(Class<T> type, Value scope)
      throws IllegalAccessException, InstantiationException, InvocationTargetException {
    Constructor[] constructors = type.getConstructors();
    if (constructors.length == 0) {
      return setters(type.newInstance(), scope, Collections.emptySet());
    }
    Constructor constructor = selectConstructor(constructors);
    if (constructor.getParameterCount() == 0) {
      return setters(type.newInstance(), scope, Collections.emptySet());
    }
    Set<Value> state = new HashSet<>();
    Object[] args = inject(scope, constructor, state::add);
    return (T) setters(constructor.newInstance(args), scope, state);
  }

  private Constructor selectConstructor(Constructor[] constructors) {
    if (constructors.length == 1) {
      return constructors[0];
    }
    Constructor result = null;
    for (Constructor constructor : constructors) {
      if (Modifier.isPublic(constructor.getModifiers())) {
        Annotation inject = constructor.getAnnotation(Inject.class);
        if (inject != null) {
          if (result == null) {
            result = constructor;
          } else {
            throw new IllegalStateException(AMBIGUOUS_CONSTRUCTOR);
          }
        }
      }
    }
    if (result == null) {
      throw new IllegalStateException(AMBIGUOUS_CONSTRUCTOR);
    }
    return result;
  }

  private <T> T setters(T newInstance, Value object, Set<Value> skip)
      throws IllegalAccessException, InstantiationException, InvocationTargetException {
    Method[] methods = newInstance.getClass().getMethods();
    for (Value value : object) {
      if (!skip.contains(value)) {
        String name = value.name();
        String setter1 = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method method = findMethod(methods, setter1);
        if (method == null) {
          method = findMethod(methods, name);
        }
        if (method != null) {
          Parameter parameter = method.getParameters()[0];
          Object arg = value(value, parameter.getType(), parameter.getParameterizedType());
          method.invoke(newInstance, arg);
        }
      }
    }
    return newInstance;
  }

  private Method findMethod(Method[] methods, String name) {
    for (Method method : methods) {
      if (method.getName().equals(name) && method.getParameterCount() == 1) {
        return method;
      }
    }
    return null;
  }

  private Object resolve(Value scope, Type type)
      throws IllegalAccessException, InvocationTargetException, InstantiationException {
    if (scope.isObject() || scope.isSimple()) {
      return newInstance(Reified.get(type).getRawType(), scope);
    } else if (scope.isMissing()) {
      // TODO: Add supports for null
      return missingToNull ? null : scope.value();
    } else {
      throw new Err.BadRequest(
          "Type mismatch: cannot convert to " + type.getTypeName());
    }
  }

  public Object[] inject(Value scope, Executable method, Consumer<Value> state)
      throws IllegalAccessException, InstantiationException, InvocationTargetException {
    Parameter[] parameters = method.getParameters();
    if (parameters.length == 0) {
      return new Object[0];
    }
    Object[] args = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      Parameter parameter = parameters[i];
      String name = paramName(parameter);
      Value value = scope.get(name);
      state.accept(value);
      args[i] = value(value, parameter.getType(), parameter.getParameterizedType());
    }
    return args;
  }

  private String paramName(Parameter parameter) {
    String name = parameter.getName();
    Named named = parameter.getAnnotation(Named.class);
    if (named != null && named.value().length() > 0) {
      name = named.value();
    }
    return name;
  }

  private Object value(Value value, Class rawType, Type type)
      throws InvocationTargetException, IllegalAccessException, InstantiationException {
    if (value.isMissing()) {
      return resolve(value, type);
    }
    if (rawType == String.class) {
      return value.get(0).value();
    }
    if (rawType == int.class || rawType == Integer.class) {
      return value.get(0).intValue();
    }
    if (rawType == long.class || rawType == Long.class) {
      return value.get(0).longValue();
    }
    if (rawType == float.class || rawType == Float.class) {
      return value.get(0).floatValue();
    }
    if (rawType == double.class || rawType == Double.class) {
      return value.get(0).doubleValue();
    }
    if (rawType == boolean.class || rawType == Boolean.class) {
      return value.get(0).booleanValue();
    }
    if (List.class.isAssignableFrom(rawType)) {
      return collection(value, (ParameterizedType) type, new ArrayList(value.size()));
    }
    if (Set.class.isAssignableFrom(rawType)) {
      return collection(value, (ParameterizedType) type, new HashSet());
    }
    if (Optional.class.isAssignableFrom(rawType)) {
      try {
        Class itemType = parameterizedType0(type);
        return Optional.ofNullable(value(value.get(0), itemType, itemType));
      } catch (Err.Missing x) {
        return Optional.empty();
      }
    }
    if (UUID.class == rawType) {
      return UUID.fromString(value.get(0).value());
    }
    if (Charset.class == rawType) {
      return Charset.forName(value.get(0).value());
    }
    /**********************************************************************************************
     * Static method: valueOf
     * ********************************************************************************************
     */
    try {
      Method valueOf = rawType.getMethod("valueOf", String.class);
      if (Modifier.isStatic(valueOf.getModifiers())) {
        return valueOf.invoke(null, value.get(0).value());
      }
    } catch (NoSuchMethodException x) {
      // Ignored
    }
    return resolve(value, type);
  }

  private Collection collection(Value scope, ParameterizedType type, Collection result)
      throws InvocationTargetException, IllegalAccessException, InstantiationException {
    Class itemType = parameterizedType0(type);
    if (scope.isArray()) {
      for (Value value : scope) {
        result.add(value(value, itemType, itemType));
      }
    } else if (scope.isObject()) {
      Iterable<Value> values = scope;
      if (scope.size() == 1) {
        // Edge cases when we want to use a list on single value objects:
        Value next = scope.iterator().next();
        if (next.isSimple() || next.isArray()) {
          values = Collections.singletonList(scope);
        }
      }
      for (Value value : values) {
        result.add(value(value, itemType, itemType));
      }
    } else if (!scope.isMissing()) {
      result.add(value(scope, itemType, itemType));
    }
    return result;
  }

  private Class parameterizedType0(Type type) {
    if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;
      return (Class) parameterizedType.getActualTypeArguments()[0];
    } else {
      // We expect a parameterized type like: List/Set/Optional, but there is no type information
      // fallback to String
      return String.class;
    }
  }
}
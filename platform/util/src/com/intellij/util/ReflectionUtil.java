/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.util;

import com.intellij.Patches;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Condition;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.reflect.ConstructorAccessor;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class ReflectionUtil {
  private static final Logger LOG = Logger.getInstance("#com.intellij.util.ReflectionUtil");

  private ReflectionUtil() {
  }

  @Nullable
  public static Type resolveVariable(@NotNull TypeVariable variable, @NotNull Class classType) {
    return resolveVariable(variable, classType, true);
  }

  @Nullable
  public static Type resolveVariable(@NotNull TypeVariable variable, @NotNull Class classType, boolean resolveInInterfacesOnly) {
    final Class aClass = getRawType(classType);
    int index = ArrayUtilRt.find(aClass.getTypeParameters(), variable);
    if (index >= 0) {
      return variable;
    }

    final Class[] classes = aClass.getInterfaces();
    final Type[] genericInterfaces = aClass.getGenericInterfaces();
    for (int i = 0; i <= classes.length; i++) {
      Class anInterface;
      if (i < classes.length) {
        anInterface = classes[i];
      }
      else {
        anInterface = aClass.getSuperclass();
        if (resolveInInterfacesOnly || anInterface == null) {
          continue;
        }
      }
      final Type resolved = resolveVariable(variable, anInterface);
      if (resolved instanceof Class || resolved instanceof ParameterizedType) {
        return resolved;
      }
      if (resolved instanceof TypeVariable) {
        final TypeVariable typeVariable = (TypeVariable)resolved;
        index = ArrayUtilRt.find(anInterface.getTypeParameters(), typeVariable);
        if (index < 0) {
          LOG.error("Cannot resolve type variable:\n" + "typeVariable = " + typeVariable + "\n" + "genericDeclaration = " +
                    declarationToString(typeVariable.getGenericDeclaration()) + "\n" + "searching in " + declarationToString(anInterface));
        }
        final Type type = i < genericInterfaces.length ? genericInterfaces[i] : aClass.getGenericSuperclass();
        if (type instanceof Class) {
          return Object.class;
        }
        if (type instanceof ParameterizedType) {
          return getActualTypeArguments((ParameterizedType)type)[index];
        }
        throw new AssertionError("Invalid type: " + type);
      }
    }
    return null;
  }

  @SuppressWarnings("HardCodedStringLiteral")
  @NotNull
  public static String declarationToString(@NotNull GenericDeclaration anInterface) {
    return anInterface.toString()
           + Arrays.asList(anInterface.getTypeParameters())
           + " loaded by " + ((Class)anInterface).getClassLoader();
  }

  public static Class<?> getRawType(@NotNull Type type) {
    if (type instanceof Class) {
      return (Class)type;
    }
    if (type instanceof ParameterizedType) {
      return getRawType(((ParameterizedType)type).getRawType());
    }
    if (type instanceof GenericArrayType) {
      //todo[peter] don't create new instance each time
      return Array.newInstance(getRawType(((GenericArrayType)type).getGenericComponentType()), 0).getClass();
    }
    assert false : type;
    return null;
  }

  @NotNull
  public static Type[] getActualTypeArguments(@NotNull ParameterizedType parameterizedType) {
    return parameterizedType.getActualTypeArguments();
  }

  @Nullable
  public static Class<?> substituteGenericType(@NotNull Type genericType, @NotNull Type classType) {
    if (genericType instanceof TypeVariable) {
      final Class<?> aClass = getRawType(classType);
      final Type type = resolveVariable((TypeVariable)genericType, aClass);
      if (type instanceof Class) {
        return (Class)type;
      }
      if (type instanceof ParameterizedType) {
        return (Class<?>)((ParameterizedType)type).getRawType();
      }
      if (type instanceof TypeVariable && classType instanceof ParameterizedType) {
        final int index = ArrayUtilRt.find(aClass.getTypeParameters(), type);
        if (index >= 0) {
          return getRawType(getActualTypeArguments((ParameterizedType)classType)[index]);
        }
      }
    }
    else {
      return getRawType(genericType);
    }
    return null;
  }

  @NotNull
  public static List<Field> collectFields(@NotNull Class clazz) {
    List<Field> result = new ArrayList<Field>();
    collectFields(clazz, result);
    return result;
  }

  @NotNull
  public static Field findField(@NotNull Class clazz, @Nullable final Class type, @NotNull final String name) throws NoSuchFieldException {
    Field result = processFields(clazz, new Condition<Field>() {
      @Override
      public boolean value(Field field) {
        return name.equals(field.getName()) && (type == null || field.getType().equals(type));
      }
    });
    if (result != null) return result;

    throw new NoSuchFieldException("Class: " + clazz + " name: " + name + " type: " + type);
  }

  @NotNull
  public static Field findAssignableField(@NotNull Class<?> clazz, @Nullable("null means any type") final Class<?> fieldType, @NotNull final String fieldName) throws NoSuchFieldException {
    Field result = processFields(clazz, new Condition<Field>() {
      @Override
      public boolean value(Field field) {
        return fieldName.equals(field.getName()) && (fieldType == null || fieldType.isAssignableFrom(field.getType()));
      }
    });
    if (result != null) return result;
    throw new NoSuchFieldException("Class: " + clazz + " fieldName: " + fieldName + " fieldType: " + fieldType);
  }

  private static void collectFields(@NotNull Class clazz, @NotNull List<Field> result) {
    final Field[] fields = clazz.getDeclaredFields();
    result.addAll(Arrays.asList(fields));
    final Class superClass = clazz.getSuperclass();
    if (superClass != null) {
      collectFields(superClass, result);
    }
    final Class[] interfaces = clazz.getInterfaces();
    for (Class each : interfaces) {
      collectFields(each, result);
    }
  }

  private static Field processFields(@NotNull Class clazz, @NotNull Condition<Field> checker) {
    for (Field field : clazz.getDeclaredFields()) {
      if (checker.value(field)) {
        field.setAccessible(true);
        return field;
      }
    }
    final Class superClass = clazz.getSuperclass();
    if (superClass != null) {
      Field result = processFields(superClass, checker);
      if (result != null) return result;
    }
    final Class[] interfaces = clazz.getInterfaces();
    for (Class each : interfaces) {
      Field result = processFields(each, checker);
      if (result != null) return result;
    }
    return null;
  }

  public static void resetField(@NotNull Class clazz, @Nullable("null means of any type") Class type, @NotNull String name)  {
    try {
      resetField(null, findField(clazz, type, name));
    }
    catch (NoSuchFieldException e) {
      LOG.info(e);
    }
  }
  public static void resetField(@NotNull Object object, @Nullable("null means any type") Class type, @NotNull String name)  {
    try {
      resetField(object, findField(object.getClass(), type, name));
    }
    catch (NoSuchFieldException e) {
      LOG.info(e);
    }
  }

  public static void resetField(@NotNull Object object, @NotNull String name) {
    try {
      resetField(object, findField(object.getClass(), null, name));
    }
    catch (NoSuchFieldException e) {
      LOG.info(e);
    }
  }

  public static void resetField(@Nullable final Object object, @NotNull Field field) {
    field.setAccessible(true);
    Class<?> type = field.getType();
    try {
      if (type.isPrimitive()) {
        if (boolean.class.equals(type)) {
          field.set(object, Boolean.FALSE);
        }
        else if (int.class.equals(type)) {
          field.set(object, Integer.valueOf(0));
        }
        else if (double.class.equals(type)) {
          field.set(object, Double.valueOf(0));
        }
        else if (float.class.equals(type)) {
          field.set(object, Float.valueOf(0));
        }
      }
      else {
        field.set(object, null);
      }
    }
    catch (IllegalAccessException e) {
      LOG.info(e);
    }
  }

  @Nullable
  public static Method findMethod(@NotNull Collection<Method> methods, @NonNls @NotNull String name, @NotNull Class... parameters) {
    for (final Method method : methods) {
      if (name.equals(method.getName()) && Arrays.equals(parameters, method.getParameterTypes())) {
        method.setAccessible(true);
        return method;
      }
    }
    return null;
  }

  @Nullable
  public static Method getMethod(@NotNull Class aClass, @NonNls @NotNull String name, @NotNull Class... parameters) {
    return findMethod(getClassPublicMethods(aClass, false), name, parameters);
  }

  @Nullable
  public static Method getDeclaredMethod(@NotNull Class aClass, @NonNls @NotNull String name, @NotNull Class... parameters) {
    return findMethod(getClassDeclaredMethods(aClass, false), name, parameters);
  }

  @Nullable
  public static Field getDeclaredField(@NotNull Class aClass, @NonNls @NotNull final String name) {
    return processFields(aClass, new Condition<Field>() {
      @Override
      public boolean value(Field field) {
        return name.equals(field.getName());
      }
    });
  }

  public static List<Method> getClassPublicMethods(@NotNull Class aClass) {
    return getClassPublicMethods(aClass, false);
  }
  
  public static List<Method> getClassPublicMethods(@NotNull Class aClass, boolean includeSynthetic) {
    Method[] methods = aClass.getMethods();
    return includeSynthetic ? Arrays.asList(methods) : filterRealMethods(methods);
  }

  public static List<Method> getClassDeclaredMethods(@NotNull Class aClass) {
    return getClassDeclaredMethods(aClass, false);
  }

  @NotNull
  public static List<Method> getClassDeclaredMethods(@NotNull Class aClass, boolean includeSynthetic) {
    Method[] methods = aClass.getDeclaredMethods();
    return includeSynthetic ? Arrays.asList(methods) : filterRealMethods(methods);
  }
  @NotNull
  public static List<Field> getClassDeclaredFields(@NotNull Class aClass) {
    Field[] fields = aClass.getDeclaredFields();
    return Arrays.asList(fields);
  }

  private static List<Method> filterRealMethods(Method[] methods) {
    List<Method> result = ContainerUtil.newArrayList();
    for (Method method : methods) {
      if (!method.isSynthetic()) {
        result.add(method);
      }
    }
    return result;
  }

  @Nullable
  public static Class getMethodDeclaringClass(@NotNull Class<?> instanceClass, @NonNls @NotNull String name, @NotNull Class... parameters) {
    Method method = getMethod(instanceClass, name, parameters);
    return method == null ? null : method.getDeclaringClass();
  }

  public static <T> T getField(@NotNull Class objectClass, Object object, @Nullable("null means any type") Class<T> fieldType, @NotNull @NonNls String fieldName) {
    try {
      final Field field = findAssignableField(objectClass, fieldType, fieldName);
      return (T)field.get(object);
    }
    catch (NoSuchFieldException e) {
      LOG.debug(e);
      return null;
    }
    catch (IllegalAccessException e) {
      LOG.debug(e);
      return null;
    }
  }

  // returns true if value was set
  public static <T> boolean setField(@NotNull Class objectClass, Object object, @Nullable("null means any type") Class<T> fieldType, @NotNull @NonNls String fieldName, T value) {
    try {
      final Field field = findAssignableField(objectClass, fieldType, fieldName);
      field.set(object, value);
      return true;
    }
    catch (NoSuchFieldException e) {
      LOG.debug(e);
    }
    catch (IllegalAccessException e) {
      LOG.debug(e);
    }
    return false;
  }

  public static Type resolveVariableInHierarchy(@NotNull TypeVariable variable, @NotNull Class aClass) {
    Type type;
    Class current = aClass;
    while ((type = resolveVariable(variable, current, false)) == null) {
      current = current.getSuperclass();
      if (current == null) {
        return null;
      }
    }
    if (type instanceof TypeVariable) {
      return resolveVariableInHierarchy((TypeVariable)type, aClass);
    }
    return type;
  }

  @NotNull
  public static <T> Constructor<T> getDefaultConstructor(@NotNull Class<T> aClass) {
    try {
      final Constructor<T> constructor = aClass.getConstructor();
      constructor.setAccessible(true);
      return constructor;
    }
    catch (NoSuchMethodException e) {
      throw new RuntimeException("No default constructor in " + aClass, e);
    }
  }

  static {
    // method getConstructorAccessorMethod is not necessary since JDK7, use acquireConstructorAccessor return value instead
    assert Patches.USE_REFLECTION_TO_ACCESS_JDK7;
  }
  private static final Method acquireConstructorAccessorMethod = getDeclaredMethod(Constructor.class, "acquireConstructorAccessor");
  private static final Method getConstructorAccessorMethod = getDeclaredMethod(Constructor.class, "getConstructorAccessor");

  @NotNull
  public static ConstructorAccessor getConstructorAccessor(@NotNull Constructor constructor) {
    constructor.setAccessible(true);
    // it is faster to invoke constructor via sun.reflect.ConstructorAccessor; it avoids AccessibleObject.checkAccess()
    try {
      acquireConstructorAccessorMethod.invoke(constructor);
      return (ConstructorAccessor)getConstructorAccessorMethod.invoke(constructor);
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  public static <T> T createInstanceViaConstructorAccessor(@NotNull ConstructorAccessor constructorAccessor,
                                                           @NotNull Object... arguments) {
    try {
      return (T)constructorAccessor.newInstance(arguments);
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  @NotNull
  public static <T> T createInstanceViaConstructorAccessor(@NotNull ConstructorAccessor constructorAccessor) {
    try {
      return (T)constructorAccessor.newInstance(ArrayUtil.EMPTY_OBJECT_ARRAY);
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * {@link Class#newInstance()} cannot instantiate private classes
   */
  @NotNull
  public static <T> T newInstance(@NotNull Class<T> aClass, @NotNull Class... parameterTypes) {
    try {
      Constructor<T> constructor = aClass.getDeclaredConstructor(parameterTypes);
      try {
        constructor.setAccessible(true);
      }
      catch (SecurityException e) {
        return aClass.newInstance();
      }
      return constructor.newInstance();
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  public static <T> T createInstance(@NotNull Constructor<T> constructor, @NotNull Object... args) {
    try {
      return constructor.newInstance(args);
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void resetThreadLocals() {
    resetField(Thread.currentThread(), null, "threadLocals");
  }

  @Nullable
  public static Class getGrandCallerClass() {
    int stackFrameCount = 3;
    Class callerClass = findCallerClass(stackFrameCount);
    while (callerClass != null && callerClass.getClassLoader() == null) { // looks like a system class
      callerClass = findCallerClass(++stackFrameCount);
    }
    if (callerClass == null) {
      callerClass = findCallerClass(2);
    }
    return callerClass;
  }


  private static class MySecurityManager extends SecurityManager {
    private static final MySecurityManager INSTANCE = new MySecurityManager();
    public Class[] getStack() {
      return getClassContext();
    }
  }

  /**
   * Returns the class this method was called 'framesToSkip' frames up the caller hierarchy.
   *
   * NOTE:
   * <b>Extremely expensive!
   * Please consider not using it.
   * These aren't the droids you're looking for!</b>
   */
  @Nullable
  public static Class findCallerClass(int framesToSkip) {
    try {
      Class[] stack = MySecurityManager.INSTANCE.getStack();
      int indexFromTop = 1 + framesToSkip;
      return stack.length > indexFromTop ? stack[indexFromTop] : null;
    }
    catch (Exception e) {
      LOG.warn(e);
      return null;
    }
  }

  public static boolean isAssignable(@NotNull Class<?> ancestor, @NotNull Class<?> descendant) {
    return ancestor == descendant || ancestor.isAssignableFrom(descendant);
  }
}

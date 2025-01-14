package com.jrasp.api.listener.ext;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public interface Behavior {

    Object invoke(Object obj, Object... args)
            throws IllegalAccessException, InvocationTargetException, InstantiationException;

    boolean isAccessible();

    void setAccessible(boolean accessFlag);

    String getName();

    Class<?>[] getParameterTypes();

    Annotation[] getAnnotations();

    int getModifiers();

    Class<?> getDeclaringClass();

    Class<?> getReturnType();

    Class<?>[] getExceptionTypes();

    Annotation[] getDeclaredAnnotations();

    AccessibleObject getTarget();

    class MethodImpl implements Behavior {

        private final Method target;

        public MethodImpl(Method target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object obj, Object... args)
                throws IllegalAccessException, InvocationTargetException, InstantiationException {
            return target.invoke(obj, args);
        }

        @Override
        public boolean isAccessible() {
            return target.isAccessible();
        }

        @Override
        public void setAccessible(boolean accessFlag) {
            target.setAccessible(accessFlag);
        }

        @Override
        public String getName() {
            return target.getName();
        }

        @Override
        public Class<?>[] getParameterTypes() {
            return target.getParameterTypes();
        }

        @Override
        public Annotation[] getAnnotations() {
            return target.getAnnotations();
        }

        @Override
        public int getModifiers() {
            return target.getModifiers();
        }

        @Override
        public Class<?> getDeclaringClass() {
            return target.getDeclaringClass();
        }

        @Override
        public Class<?> getReturnType() {
            return target.getReturnType();
        }

        @Override
        public Class<?>[] getExceptionTypes() {
            return target.getExceptionTypes();
        }

        @Override
        public Annotation[] getDeclaredAnnotations() {
            return target.getDeclaredAnnotations();
        }

        @Override
        public AccessibleObject getTarget() {
            return target;
        }

        @Override
        public int hashCode() {
            return target.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return target.equals(obj);
        }
    }

    class ConstructorImpl implements Behavior {

        private final Constructor<?> target;

        public ConstructorImpl(Constructor<?> target) {
            this.target = target;
        }


        @Override
        public Object invoke(Object obj, Object... args)
                throws IllegalAccessException, InvocationTargetException, InstantiationException {
            return target.newInstance(args);
        }

        @Override
        public boolean isAccessible() {
            return target.isAccessible();
        }

        @Override
        public void setAccessible(boolean accessFlag) {
            target.setAccessible(accessFlag);
        }

        @Override
        public String getName() {
            return "<init>";
        }

        @Override
        public Class<?>[] getParameterTypes() {
            return target.getParameterTypes();
        }

        @Override
        public Annotation[] getAnnotations() {
            return target.getAnnotations();
        }

        @Override
        public int getModifiers() {
            return target.getModifiers();
        }

        @Override
        public Class<?> getDeclaringClass() {
            return target.getDeclaringClass();
        }

        @Override
        public Class<?> getReturnType() {
            return target.getDeclaringClass();
        }

        @Override
        public Class<?>[] getExceptionTypes() {
            return target.getExceptionTypes();
        }

        @Override
        public Annotation[] getDeclaredAnnotations() {
            return target.getDeclaredAnnotations();
        }

        @Override
        public AccessibleObject getTarget() {
            return target;
        }

        @Override
        public int hashCode() {
            return target.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return target.equals(obj);
        }

    }
}
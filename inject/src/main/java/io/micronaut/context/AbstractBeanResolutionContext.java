/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.context;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.CachedEnvironment;
import io.micronaut.context.annotation.InjectScope;
import io.micronaut.context.env.ConfigurationPath;
import io.micronaut.context.exceptions.CircularDependencyException;
import io.micronaut.context.scope.CustomScope;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.naming.Named;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ArgumentCoercible;
import io.micronaut.core.util.ObjectUtils;
import io.micronaut.inject.*;

import java.util.*;
import java.util.stream.Stream;

/**
 * Default implementation of the {@link BeanResolutionContext} interface.
 *
 * @author Graeme Rocher
 * @since 1.2.3
 */
@Internal
public abstract class AbstractBeanResolutionContext implements BeanResolutionContext {

    private static final String CONSTRUCTOR_METHOD_NAME = "<init>";
    protected final DefaultBeanContext context;
    protected final BeanDefinition<?> rootDefinition;
    protected final Path path;
    private Map<CharSequence, Object> attributes;
    private Qualifier<?> qualifier;
    private List<BeanRegistration<?>> dependentBeans;
    private BeanRegistration<?> dependentFactory;

    private ConfigurationPath configurationPath;

    /**
     * @param context        The bean context
     * @param rootDefinition The bean root definition
     */
    @Internal
    protected AbstractBeanResolutionContext(DefaultBeanContext context, BeanDefinition<?> rootDefinition) {
        this.context = context;
        this.rootDefinition = rootDefinition;
        this.path = new DefaultPath();
    }

    @Override
    public ConfigurationPath getConfigurationPath() {
        if (configurationPath != null) {
            return configurationPath;
        } else {
            this.configurationPath = ConfigurationPath.newPath();
            return configurationPath;
        }
    }

    @Override
    public ConfigurationPath setConfigurationPath(ConfigurationPath configurationPath) {
        ConfigurationPath old = this.configurationPath;
        this.configurationPath = configurationPath;
        return old;
    }

    @NonNull
    @Override
    public <T> T getBean(@NonNull Argument<T> beanType, @Nullable Qualifier<T> qualifier) {
        return context.getBean(this, beanType, qualifier);
    }

    @NonNull
    @Override
    public <T> Collection<T> getBeansOfType(@NonNull Argument<T> beanType, @Nullable Qualifier<T> qualifier) {
        return context.getBeansOfType(this, beanType, qualifier);
    }

    @NonNull
    @Override
    public <T> Stream<T> streamOfType(@NonNull Argument<T> beanType, @Nullable Qualifier<T> qualifier) {
        return context.streamOfType(this, beanType, qualifier);
    }

    @Override
    public <V> Map<String, V> mapOfType(Argument<V> beanType, Qualifier<V> qualifier) {
        return context.mapOfType(this, beanType, qualifier);
    }

    @NonNull
    @Override
    public <T> Optional<T> findBean(@NonNull Argument<T> beanType, @Nullable Qualifier<T> qualifier) {
        return context.findBean(this, beanType, qualifier);
    }

    @NonNull
    @Override
    public <T> Collection<BeanRegistration<T>> getBeanRegistrations(@NonNull Argument<T> beanType, @Nullable Qualifier<T> qualifier) {
        return context.getBeanRegistrations(this, beanType, qualifier);
    }

    /**
     * Copy the state from a previous resolution context.
     *
     * @param context The previous context
     */
    public void copyStateFrom(@NonNull AbstractBeanResolutionContext context) {
        path.addAll(context.path);
        qualifier = context.qualifier;
        if (context.attributes != null) {
            getAttributesOrCreate().putAll(context.attributes);
        }
    }

    @Override
    public <T> void addDependentBean(BeanRegistration<T> beanRegistration) {
        if (beanRegistration.getBeanDefinition() == rootDefinition) {
            // Don't add self
            return;
        }
        if (dependentBeans == null) {
            dependentBeans = new ArrayList<>(3);
        }
        dependentBeans.add(beanRegistration);
    }

    @Override
    public void destroyInjectScopedBeans() {
        final CustomScope<?> injectScope = context.getCustomScopeRegistry()
                .findScope(InjectScope.class.getName())
                .orElse(null);
        if (injectScope instanceof LifeCycle<?>) {
            ((LifeCycle<?>) injectScope).stop();
        }
    }

    @NonNull
    @Override
    public List<BeanRegistration<?>> getAndResetDependentBeans() {
        if (dependentBeans == null) {
            return Collections.emptyList();
        }
        final List<BeanRegistration<?>> registrations = Collections.unmodifiableList(dependentBeans);
        dependentBeans = null;
        return registrations;
    }

    @Override
    public void markDependentAsFactory() {
        if (dependentBeans != null) {
            if (dependentBeans.isEmpty()) {
                return;
            }
            if (dependentBeans.size() != 1) {
                throw new IllegalStateException("Expected only one bean dependent!");
            }
            dependentFactory = dependentBeans.remove(0);
        }
    }

    @Override
    public BeanRegistration<?> getAndResetDependentFactoryBean() {
        BeanRegistration<?> result = this.dependentFactory;
        this.dependentFactory = null;
        return result;
    }

    @Override
    public List<BeanRegistration<?>> popDependentBeans() {
        List<BeanRegistration<?>> result = this.dependentBeans;
        this.dependentBeans = null;
        return result;
    }

    @Override
    public void pushDependentBeans(List<BeanRegistration<?>> dependentBeans) {
        if (this.dependentBeans != null && !this.dependentBeans.isEmpty()) {
            throw new IllegalStateException("Found existing dependent beans!");
        }
        this.dependentBeans = dependentBeans;
    }

    @Override
    public final BeanContext getContext() {
        return context;
    }

    @Override
    public final BeanDefinition getRootDefinition() {
        return rootDefinition;
    }

    @Override
    public final Path getPath() {
        return path;
    }

    @Override
    public final Object setAttribute(CharSequence key, Object value) {
        return getAttributesOrCreate().put(key, value);
    }

    /**
     * @param key The key
     * @return The attribute value
     */
    @Override
    public final Object getAttribute(CharSequence key) {
        if (attributes == null) {
            return null;
        }
        return attributes.get(key);
    }

    @Override
    public final Object removeAttribute(CharSequence key) {
        if (attributes != null && key != null) {
            return attributes.remove(key);
        }
        return null;
    }

    @Override
    public Map<CharSequence, Object> getAttributes() {
        return attributes;
    }

    @Override
    public void setAttributes(Map<CharSequence, Object> attributes) {
        this.attributes = attributes;
    }

    @Nullable
    @Override
    public Qualifier<?> getCurrentQualifier() {
        return qualifier;
    }

    @Override
    public void setCurrentQualifier(@Nullable Qualifier<?> qualifier) {
        this.qualifier = qualifier;
    }

    @Override
    public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
        if (attributes == null) {
            return Optional.empty();
        }
        Object value = attributes.get(name);
        if (value != null && conversionContext.getArgument().getType().isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }

    @Override
    public <T> Optional<T> get(CharSequence name, Class<T> requiredType) {
        if (attributes == null) {
            return Optional.empty();
        }
        Object value = attributes.get(name);
        if (requiredType.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }

    protected void onNewSegment(Segment<?, ?> segment) {
        //no-op
    }

    @NonNull
    private Map<CharSequence, Object> getAttributesOrCreate() {
        if (attributes == null) {
            attributes = new LinkedHashMap<>(2);
        }
        return attributes;
    }

    /**
     * Class that represents a default path.
     */
    class DefaultPath extends LinkedList<Segment<?, ?>> implements Path {

        public static final String RIGHT_ARROW = " --> ";
        private static final String CIRCULAR_ERROR_MSG = "Circular dependency detected";

        DefaultPath() {
        }

        @Override
        public String toString() {
            Iterator<Segment<?, ?>> i = descendingIterator();
            StringBuilder pathString = new StringBuilder();
            while (i.hasNext()) {
                pathString.append(i.next().toString());
                if (i.hasNext()) {
                    pathString.append(RIGHT_ARROW);
                }
            }
            return pathString.toString();
        }

        @SuppressWarnings("MagicNumber")
        @Override
        public String toCircularString() {
            Iterator<Segment<?, ?>> i = descendingIterator();
            StringBuilder pathString = new StringBuilder();
            String ls = CachedEnvironment.getProperty("line.separator");
            while (i.hasNext()) {
                String segmentString = i.next().toString();
                pathString.append(segmentString);
                if (i.hasNext()) {
                    pathString.append(RIGHT_ARROW);
                } else {
                    int totalLength = pathString.length() - 3;
                    String spaces = String.join("", Collections.nCopies(totalLength, " "));
                    pathString.append(ls)
                            .append("^")
                            .append(spaces)
                            .append("|")
                            .append(ls)
                            .append("|")
                            .append(spaces)
                            .append("|").append(ls)
                            .append("|")
                            .append(spaces)
                            .append("|").append(ls).append('+');
                    pathString.append(String.join("", Collections.nCopies(totalLength, "-"))).append('+');
                }
            }
            return pathString.toString();
        }

        @Override
        public Optional<Segment<?, ?>> currentSegment() {
            return Optional.ofNullable(peek());
        }

        @Override
        public Path pushConstructorResolve(BeanDefinition declaringType, Argument argument) {
            ConstructorInjectionPoint<?> constructor = declaringType.getConstructor();
            if (constructor instanceof MethodInjectionPoint<?, ?> methodInjectionPoint) {
                return pushConstructorResolve(declaringType, methodInjectionPoint.getName(), argument, constructor.getArguments());
            }
            return pushConstructorResolve(declaringType, CONSTRUCTOR_METHOD_NAME, argument, constructor.getArguments());
        }

        @Override
        public Path pushConstructorResolve(BeanDefinition declaringType, String methodName, Argument argument, Argument[] arguments) {
            if (CONSTRUCTOR_METHOD_NAME.equals(methodName)) {
                ConstructorSegment constructorSegment = new ConstructorArgumentSegment(declaringType, methodName, argument, arguments);
                detectCircularDependency(declaringType, argument, constructorSegment);
            } else {
                Segment<?, ?> previous = peek();
                MethodSegment<?, ?> methodSegment = new MethodArgumentSegment(declaringType, methodName, argument, arguments, previous instanceof MethodSegment ? (MethodSegment) previous : null);
                if (contains(methodSegment)) {
                    throw new CircularDependencyException(AbstractBeanResolutionContext.this, argument, CIRCULAR_ERROR_MSG);
                } else {
                    push(methodSegment);
                }
            }
            return this;
        }

        @Override
        public Path pushBeanCreate(BeanDefinition<?> declaringType, Argument<?> beanType) {
            return pushConstructorResolve(declaringType, beanType);
        }

        @Override
        public Path pushMethodArgumentResolve(BeanDefinition declaringType, MethodInjectionPoint methodInjectionPoint, Argument argument) {
            Segment<?, ?> previous = peek();
            MethodSegment<?, ?> methodSegment = new MethodArgumentSegment(declaringType, methodInjectionPoint.getName(), argument,
                    methodInjectionPoint.getArguments(), previous instanceof MethodSegment ? (MethodSegment) previous : null);
            if (contains(methodSegment)) {
                throw new CircularDependencyException(AbstractBeanResolutionContext.this, methodInjectionPoint, argument, CIRCULAR_ERROR_MSG);
            } else {
                push(methodSegment);
            }

            return this;
        }

        @Override
        public Path pushMethodArgumentResolve(BeanDefinition declaringType, String methodName, Argument argument, Argument[] arguments) {
            Segment<?, ?> previous = peek();
            MethodSegment<?, ?> methodSegment = new MethodArgumentSegment(declaringType, methodName, argument, arguments, previous instanceof MethodSegment ? (MethodSegment) previous : null);
            if (contains(methodSegment)) {
                throw new CircularDependencyException(AbstractBeanResolutionContext.this, declaringType, methodName, argument, CIRCULAR_ERROR_MSG);
            } else {
                push(methodSegment);
            }

            return this;
        }

        @Override
        public Path pushFieldResolve(BeanDefinition declaringType, FieldInjectionPoint fieldInjectionPoint) {
            FieldSegment<?, ?> fieldSegment = new FieldSegment<>(declaringType, fieldInjectionPoint.asArgument());
            if (contains(fieldSegment)) {
                throw new CircularDependencyException(AbstractBeanResolutionContext.this, fieldInjectionPoint, CIRCULAR_ERROR_MSG);
            } else {
                push(fieldSegment);
            }
            return this;
        }

        @Override
        public Path pushFieldResolve(BeanDefinition declaringType, Argument fieldAsArgument) {
            FieldSegment<?, ?> fieldSegment = new FieldSegment<>(declaringType, fieldAsArgument);
            if (contains(fieldSegment)) {
                throw new CircularDependencyException(AbstractBeanResolutionContext.this, declaringType, fieldAsArgument.getName(), CIRCULAR_ERROR_MSG);
            } else {
                push(fieldSegment);
            }
            return this;
        }

        @Override
        public Path pushAnnotationResolve(BeanDefinition beanDefinition, Argument annotationMemberBeanAsArgument) {
            AnnotationSegment annotationSegment = new AnnotationSegment(beanDefinition, annotationMemberBeanAsArgument);
            if (contains(annotationSegment)) {
                throw new CircularDependencyException(AbstractBeanResolutionContext.this, beanDefinition, annotationMemberBeanAsArgument.getName(), CIRCULAR_ERROR_MSG);
            } else {
                push(annotationSegment);
            }
            return this;
        }

        private void detectCircularDependency(BeanDefinition declaringType, Argument argument, Segment constructorSegment) {
            if (contains(constructorSegment)) {
                Segment last = peek();
                if (last != null) {

                    BeanDefinition declaringBean = last.getDeclaringType();
                    // if the currently injected segment is a constructor argument and the type to be constructed is the
                    // same as the candidate, then filter out the candidate to avoid a circular injection problem
                    if (!declaringBean.equals(declaringType)) {
                        if (declaringType instanceof ProxyBeanDefinition<?> proxyBeanDefinition) {
                            // take into account proxies
                            if (!proxyBeanDefinition.getTargetDefinitionType().equals(declaringBean.getClass())) {
                                throw new CircularDependencyException(AbstractBeanResolutionContext.this, argument, CIRCULAR_ERROR_MSG);
                            } else {
                                push(constructorSegment);
                            }
                        } else if (declaringBean instanceof ProxyBeanDefinition<?> proxyBeanDefinition) {
                            // take into account proxies
                            if (!proxyBeanDefinition.getTargetDefinitionType().equals(declaringType.getClass())) {
                                throw new CircularDependencyException(AbstractBeanResolutionContext.this, argument, CIRCULAR_ERROR_MSG);
                            } else {
                                push(constructorSegment);
                            }
                        } else {
                            throw new CircularDependencyException(AbstractBeanResolutionContext.this, argument, CIRCULAR_ERROR_MSG);
                        }
                    } else {
                        push(constructorSegment);
                    }
                } else {
                    throw new CircularDependencyException(AbstractBeanResolutionContext.this, argument, CIRCULAR_ERROR_MSG);
                }
            } else {
                push(constructorSegment);
            }
        }

        @Override
        public void push(Segment<?, ?> segment) {
            super.push(segment);
            AbstractBeanResolutionContext.this.onNewSegment(segment);
        }
    }

    /**
     * A segment that represents a method argument.
     */
    public static class ConstructorArgumentSegment extends ConstructorSegment implements ArgumentInjectionPoint {
        public ConstructorArgumentSegment(BeanDefinition declaringType, String methodName, Argument argument, Argument[] arguments) {
            super(declaringType, methodName, argument, arguments);
        }

        @Override
        public CallableInjectionPoint getOuterInjectionPoint() {
            throw new UnsupportedOperationException("Outer injection point inaccessible from here");
        }

        @Override
        public BeanDefinition getDeclaringBean() {
            return getDeclaringType();
        }

    }

    /**
     * A segment that represents a constructor.
     */
    public static class ConstructorSegment extends AbstractSegment {

        private final String methodName;
        private final Argument[] arguments;

        /**
         * @param declaringClass The declaring class
         * @param methodName     The methodName
         * @param argument       The argument
         * @param arguments      The arguments
         */
        ConstructorSegment(BeanDefinition declaringClass, String methodName, Argument argument, Argument[] arguments) {
            super(declaringClass, declaringClass.getBeanType().getName(), argument);
            this.methodName = methodName;
            this.arguments = arguments;
        }

        @Override
        public String toString() {
            StringBuilder baseString;
            if (CONSTRUCTOR_METHOD_NAME.equals(methodName)) {
                baseString = new StringBuilder("new ");
                baseString.append(getDeclaringType().getBeanType().getSimpleName());
            } else {
                baseString = new StringBuilder(getDeclaringType().getBeanType().getSimpleName()).append('.');
                baseString.append(methodName);
            }
            outputArguments(baseString, arguments);
            return baseString.toString();
        }

        @Override
        public InjectionPoint getInjectionPoint() {
            ConstructorInjectionPoint constructorInjectionPoint = getDeclaringType().getConstructor();
            return new ArgumentInjectionPoint() {
                @NonNull
                @Override
                public CallableInjectionPoint getOuterInjectionPoint() {
                    return constructorInjectionPoint;
                }

                @NonNull
                @Override
                public Argument getArgument() {
                    return ConstructorSegment.this.getArgument();
                }

                @Override
                public BeanDefinition getDeclaringBean() {
                    return constructorInjectionPoint.getDeclaringBean();
                }

                @Override
                public AnnotationMetadata getAnnotationMetadata() {
                    return getArgument().getAnnotationMetadata();
                }
            };
        }

    }

    /**
     * A segment that represents a method argument.
     */
    public static class MethodArgumentSegment extends MethodSegment implements ArgumentInjectionPoint {
        private final MethodSegment outer;

        public MethodArgumentSegment(BeanDefinition declaringType, String methodName, Argument argument, Argument[] arguments, MethodSegment outer) {
            super(declaringType, methodName, argument, arguments);
            this.outer = outer;
        }

        @Override
        public CallableInjectionPoint getOuterInjectionPoint() {
            if (outer == null) {
                throw new IllegalStateException("Outer argument inaccessible");
            }
            return outer;
        }

        @Override
        public String toString() {
            BeanDefinition<?> declaringBean = getDeclaringBean();
            if (declaringBean.hasAnnotation(Factory.class)) {
                ConstructorInjectionPoint<?> constructor = declaringBean.getConstructor();
                var baseString = new StringBuilder(constructor.getDeclaringBeanType().getSimpleName()).append('.');
                baseString.append(getName());
                outputArguments(baseString, getArguments());
                return baseString.toString();
            } else {
                return super.toString();
            }
        }
    }

    /**
     * A segment that represents a method.
     */
    public static class MethodSegment<B, T> extends AbstractSegment<B, T> implements CallableInjectionPoint<B> {

        private final Argument[] arguments;

        /**
         * @param declaringType      The declaring type
         * @param methodName         The method name
         * @param argument           The argument
         * @param arguments          The arguments
         */
        MethodSegment(BeanDefinition<B> declaringType, String methodName, Argument<T> argument, Argument[] arguments) {
            super(declaringType, methodName, argument);
            this.arguments = arguments;
        }

        @Override
        public String toString() {
            StringBuilder baseString = new StringBuilder(getDeclaringType().getBeanType().getSimpleName()).append('.');
            baseString.append(getName());
            outputArguments(baseString, arguments);
            return baseString.toString();
        }

        @Override
        public InjectionPoint<B> getInjectionPoint() {
            return this;
        }

        @Override
        public BeanDefinition<B> getDeclaringBean() {
            return getDeclaringType();
        }

        @Override
        public Argument<?>[] getArguments() {
            return arguments;
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return getArgument().getAnnotationMetadata();
        }
    }

    /**
     * A segment that represents a field.
     */
    public static class FieldSegment<B, T> extends AbstractSegment<B, T> implements InjectionPoint<B>, ArgumentCoercible<T>, ArgumentInjectionPoint<B, T> {

        /**
         * @param declaringClass     The declaring class
         * @param argument           The argument
         */
        FieldSegment(BeanDefinition<B> declaringClass, Argument<T> argument) {
            super(declaringClass, argument.getName(), argument);
        }

        @Override
        public String toString() {
            return getDeclaringType().getBeanType().getSimpleName() + "." + getName();
        }

        @Override
        public InjectionPoint<B> getInjectionPoint() {
            return this;
        }

        @Override
        public BeanDefinition<B> getDeclaringBean() {
            return getDeclaringType();
        }

        @Override
        public CallableInjectionPoint<B> getOuterInjectionPoint() {
            throw new UnsupportedOperationException("Outer injection point not retrievable from here");
        }

        @Override
        public Argument<T> asArgument() {
            return getArgument();
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return getArgument().getAnnotationMetadata();
        }
    }

    /**
     * A segment that represents annotation.
     *
     * @since 3.3.0
     */
    public static final class AnnotationSegment<B> extends AbstractSegment<B, B> implements InjectionPoint<B> {
        /**
         * @param beanDefinition The bean definition
         * @param argument       The argument
         */
        AnnotationSegment(BeanDefinition<B> beanDefinition, Argument<B> argument) {
            super(beanDefinition, argument.getName(), argument);
        }

        @Override
        public String toString() {
            return getName();
        }

        @Override
        public InjectionPoint<B> getInjectionPoint() {
            return this;
        }

        @Override
        public BeanDefinition<B> getDeclaringBean() {
            return getDeclaringType();
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return getArgument().getAnnotationMetadata();
        }
    }

    /**
     * Abstract class for a Segment.
     */
    abstract static class AbstractSegment<B, T> implements Segment<B, T>, Named {
        private final BeanDefinition<B> declaringComponent;
        private final String name;
        private final Argument<T> argument;

        /**
         * @param declaringClass The declaring class
         * @param name           The name
         * @param argument       The argument
         */
        AbstractSegment(BeanDefinition<B> declaringClass, String name, Argument<T> argument) {
            this.declaringComponent = declaringClass;
            this.name = name;
            this.argument = argument;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public BeanDefinition<B> getDeclaringType() {
            return declaringComponent;
        }

        @Override
        public Argument<T> getArgument() {
            return argument;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            AbstractSegment that = (AbstractSegment) o;

            return declaringComponent.equals(that.declaringComponent) && name.equals(that.name) && argument.equals(that.argument);
        }

        @Override
        public int hashCode() {
            return ObjectUtils.hash(declaringComponent, name, argument);
        }

        /**
         * @param baseString The base string
         * @param arguments  The arguments
         */
        void outputArguments(StringBuilder baseString, Argument[] arguments) {
            baseString.append('(');
            for (int i = 0; i < arguments.length; i++) {
                Argument<?> argument = arguments[i];
                boolean isInjectedArgument = argument.equals(getArgument());
                if (isInjectedArgument) {
                    baseString.append('[');
                }
                baseString.append(argument);
                if (isInjectedArgument) {
                    baseString.append(']');
                }
                if (i != arguments.length - 1) {
                    baseString.append(',');
                }
            }
            baseString.append(')');
        }
    }
}

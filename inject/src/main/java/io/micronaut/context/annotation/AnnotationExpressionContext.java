/*
 * Copyright 2017-2022 original authors
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
package io.micronaut.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A meta annotation used to extend {@link io.micronaut.core.expressions.EvaluatedExpression}
 * context with specified type. Being an expression context means that expressions can reference
 * methods and properties of this object directly with # prefix. The difference between this
 * annotation and {@link EvaluatedExpressionContext} is that this one allows to specify context
 * that will only be scoped to this concrete annotation or annotation member.
 *
 * @author Sergey Gavrilov
 * @since 4.0.0
 */
@Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface AnnotationExpressionContext {
    Class<?> value();
}

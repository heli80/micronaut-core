/*
 * Copyright 2017 original authors
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
package org.particleframework.inject.annotation;

import org.particleframework.context.exceptions.BeanContextException;

/**
 * An exception that occurs constructing {@link org.particleframework.core.annotation.AnnotationMetadata}
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class AnnotationMetadataException extends BeanContextException {
    public AnnotationMetadataException(String message, Throwable cause) {
        super(message, cause);
    }

    public AnnotationMetadataException(String message) {
        super(message);
    }
}

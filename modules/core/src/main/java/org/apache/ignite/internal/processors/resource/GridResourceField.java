/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.resource;

import org.apache.ignite.internal.util.typedef.internal.*;

import java.lang.annotation.*;
import java.lang.reflect.*;

/**
 * Wrapper for data where resource should be injected.
 * Bean contains {@link Field} and {@link Annotation} for that class field.
 */
class GridResourceField {
    /** Field where resource should be injected. */
    private final Field field;

    /** Resource annotation. */
    private final Annotation ann;

    /**
     * Creates new bean.
     *
     * @param field Field where resource should be injected.
     * @param ann Resource annotation.
     */
    GridResourceField(Field field, Annotation ann) {
        assert field != null;
        assert ann != null || GridResourceUtils.mayRequireResources(field);

        this.field = field;
        this.ann = ann;
    }

    /**
     * Gets class field object.
     *
     * @return Class field.
     */
    public Field getField() {
        return field;
    }

    /**
     * Gets annotation for class field object.
     *
     * @return Field annotation.
     */
    public Annotation getAnnotation() {
        return ann;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridResourceField.class, this);
    }
}

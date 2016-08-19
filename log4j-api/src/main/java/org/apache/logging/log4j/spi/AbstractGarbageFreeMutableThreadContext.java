/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.spi;

import java.util.Collections;
import java.util.Map;

import org.apache.logging.log4j.util.PropertiesUtil;

/**
 * Garbage-free ThreadContextMap implementation backed by {@code MutableContextData}.
 */
public abstract class AbstractGarbageFreeMutableThreadContext implements ThreadContextMap {
    /**
     * Property name ({@value} ) for selecting {@code InheritableThreadLocal} (value "true") or plain
     * {@code ThreadLocal} (value is not "true") in the implementation.
     */
    public static final String INHERITABLE_MAP = "isThreadContextMapInheritable";

    private final ThreadLocal<MutableContextData> localMap;

    public AbstractGarbageFreeMutableThreadContext() {
        this.localMap = createThreadLocalMap();
    }

    // LOG4J2-479: by default, use a plain ThreadLocal, only use InheritableThreadLocal if configured.
    // (This method is package protected for JUnit tests.)
    private ThreadLocal<MutableContextData> createThreadLocalMap() {
        final PropertiesUtil managerProps = PropertiesUtil.getProperties();
        final boolean inheritable = managerProps.getBooleanProperty(INHERITABLE_MAP);
        if (inheritable) {
            return new InheritableThreadLocal<MutableContextData>() {
                @Override
                protected MutableContextData childValue(final MutableContextData parentValue) {
                    return parentValue != null ? createMutableContextData(parentValue) : null;
                }
            };
        }
        // if not inheritable, return plain ThreadLocal with null as initial value
        return new ThreadLocal<>();
    }

    protected abstract MutableContextData createMutableContextData();

    protected abstract MutableContextData createMutableContextData(final MutableContextData original);

    private MutableContextData getThreadLocalMap() {
        MutableContextData map = localMap.get();
        if (map == null) {
            map = createMutableContextData();
            localMap.set(map);
        }
        return map;
    }

    @Override
    public void put(final String key, final String value) {
        getThreadLocalMap().putValue(key, value);
    }

    @Override
    public void putAll(final Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        final MutableContextData map = getThreadLocalMap();
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            map.putValue(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public String get(final String key) {
        final MutableContextData map = localMap.get();
        return map == null ? null : (String) map.getValue(key);
    }

    @Override
    public void remove(final String key) {
        final MutableContextData map = localMap.get();
        if (map != null) {
            map.remove(key);
        }
    }

    @Override
    public void clear() {
        localMap.remove();
    }

    @Override
    public boolean containsKey(final String key) {
        final MutableContextData map = localMap.get();
        return map != null && map.containsKey(key);
    }

    @Override
    public Map<String, String> getCopy() {
        final MutableContextData map = localMap.get();
        return map == null ? Collections.<String, String>emptyMap() : map.asMap();
    }

    public ContextData getContextData() {
        return localMap.get();
    }

    @Override
    public Map<String, String> getImmutableMapOrNull() {
        final MutableContextData map = localMap.get();
        return map == null ? null : Collections.unmodifiableMap(map.asMap());
    }

    @Override
    public boolean isEmpty() {
        final MutableContextData map = localMap.get();
        return map == null || map.size() == 0;
    }

    @Override
    public String toString() {
        final MutableContextData map = localMap.get();
        return map == null ? "{}" : map.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        final MutableContextData map = this.localMap.get();
        result = prime * result + ((map == null) ? 0 : map.hashCode());
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof ThreadContextMap)) {
            return false;
        }
        final ThreadContextMap other = (ThreadContextMap) obj;
        final Map<String, String> map = this.getImmutableMapOrNull();
        final Map<String, String> otherMap = other.getImmutableMapOrNull();
        if (map == null) {
            if (otherMap != null) {
                return false;
            }
        } else if (!map.equals(otherMap)) {
            return false;
        }
        return true;
    }
}
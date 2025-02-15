/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.beans.factory;

import org.apache.dubbo.common.beans.ScopeBeanException;
import org.apache.dubbo.common.extension.ExtensionAccessor;
import org.apache.dubbo.common.extension.ExtensionAccessorAware;
import org.apache.dubbo.common.extension.ExtensionPostProcessor;
import org.apache.dubbo.common.utils.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A bean factory for internal sharing.
 */
public class ScopeBeanFactory {

    private final ScopeBeanFactory parent;
    private ExtensionAccessor extensionAccessor;
    private List<ExtensionPostProcessor> extensionPostProcessors;
    private Map<Class, AtomicInteger> beanNameIdCounterMap = new ConcurrentHashMap<>();
    private List<BeanInfo> registeredBeanInfos = Collections.synchronizedList(new ArrayList<>());

    public ScopeBeanFactory(ScopeBeanFactory parent, ExtensionAccessor extensionAccessor) {
        this.parent = parent;
        this.extensionAccessor = extensionAccessor;
        extensionPostProcessors = extensionAccessor.getExtensionDirector().getExtensionPostProcessors();
    }

    public <T> T registerBean(Class<T> bean) throws ScopeBeanException {
        return this.registerBean(null, bean);
    }

    public <T> T registerBean(String name, Class<T> clazz) throws ScopeBeanException {
        T instance = getBean(name, clazz);
        if (instance != null) {
            throw new ScopeBeanException("already exists bean with same name and type, name=" + name + ", type=" + clazz.getName());
        }
        try {
            instance = clazz.newInstance();
        } catch (Throwable e) {
            throw new ScopeBeanException("create bean instance failed, type=" + clazz.getName());
        }
        registerBean(name, instance);
        return instance;
    }

    public void registerBean(Object bean) {
        this.registerBean(null, bean);
    }

    public void registerBean(String name, Object bean) {
        // avoid duplicated register same bean
        if (containsBean(name, bean)) {
            return;
        }

        Class<?> beanClass = bean.getClass();
        if (name == null) {
            name = beanClass.getName() + "#" + getNextId(beanClass);
        }
        initializeBean(name, bean);

        registeredBeanInfos.add(new BeanInfo(name, bean));
    }

    public <T> T registerBeanIfAbsent(Class<T> type) {
        return registerBeanIfAbsent(null, type);
    }

    public <T> T registerBeanIfAbsent(String name, Class<T> type) {
        T bean = getBean(name, type);
        if (bean == null) {
            bean = registerBean(name, type);
        }
        return bean;
    }

    public <T> T registerBeanIfAbsent(Class<T> type, Function<? super Class<T>, ? extends T> mappingFunction) {
        return registerBeanIfAbsent(null, type, mappingFunction);
    }

    public <T> T registerBeanIfAbsent(String name, Class<T> type, Function<? super Class<T>, ? extends T> mappingFunction) {
        T bean = getBean(name, type);
        if (bean == null) {
            //TODO add lock
            bean = mappingFunction.apply(type);
            registerBean(name, bean);
        }
        return bean;
    }

    public <T> T initializeBean(T bean) {
        this.initializeBean(null, bean);
        return bean;
    }

    private void initializeBean(String name, Object bean) {
        try {
            if (bean instanceof ExtensionAccessorAware) {
                ((ExtensionAccessorAware) bean).setExtensionAccessor(extensionAccessor);
            }
            for (ExtensionPostProcessor processor : extensionPostProcessors) {
                processor.postProcessAfterInitialization(bean, name);
            }
        } catch (Exception e) {
            throw new ScopeBeanException("register bean failed! name=" + name + ", type=" + bean.getClass().getName(), e);
        }
    }

    private boolean containsBean(String name, Object bean) {
        for (BeanInfo beanInfo : registeredBeanInfos) {
            if (beanInfo.instance == bean &&
                (name == null || StringUtils.isEquals(name, beanInfo.name))) {
                return true;
            }
        }
        return false;
    }

    private int getNextId(Class<?> beanClass) {
        return beanNameIdCounterMap.computeIfAbsent(beanClass, key -> new AtomicInteger()).incrementAndGet();
    }

    public <T> T getBean(Class<T> type) {
        return this.getBean(null, type);
    }

    public <T> T getBean(String name, Class<T> type) {
        T bean = getBeanInternal(name, type);
        if (bean == null && parent != null) {
            return parent.getBean(name, type);
        }
        return bean;
    }

    private <T> T getBeanInternal(String name, Class<T> type) {
        List<BeanInfo> candidates = null;
        for (BeanInfo beanInfo : registeredBeanInfos) {
            // if required bean type is same class/superclass/interface of the registered bean
            if (type.isAssignableFrom(beanInfo.instance.getClass())) {
                if (StringUtils.isEquals(beanInfo.name, name)) {
                    return (T) beanInfo.instance;
                } else {
                    if (candidates == null) {
                        candidates = new ArrayList<>();
                    }
                    candidates.add(beanInfo);
                }
            }
        }

        // if bean name not matched and only single candidate
        if (candidates != null) {
            if (candidates.size() == 1) {
                return (T) candidates.get(0).instance;
            } else if (candidates.size() > 1) {
                List<String> candidateBeanNames = candidates.stream().map(beanInfo -> beanInfo.name).collect(Collectors.toList());
                throw new ScopeBeanException("expected single matching bean but found " + candidates.size() + " candidates for type [" + type.getName() + "]: " + candidateBeanNames);
            }
        }
        return null;
    }

    static class BeanInfo {
        private String name;
        private Object instance;

        public BeanInfo(String name, Object instance) {
            this.name = name;
            this.instance = instance;
        }
    }
}

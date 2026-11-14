package com.orqix.sdk.spring;

import com.orqix.sdk.worker.Job;
import com.orqix.sdk.worker.JobHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/** Collects the {@link OrqixWorker} methods of the application's beans and adapts each to a {@link JobHandler}. */
public class OrqixWorkerMethods implements BeanPostProcessor {

    private final Map<String, JobHandler> handlers = new LinkedHashMap<>();
    private final Map<String, String> owners = new LinkedHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> type = AopUtils.getTargetClass(bean);
        Map<Method, OrqixWorker> methods = MethodIntrospector.selectMethods(type,
                (MethodIntrospector.MetadataLookup<OrqixWorker>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, OrqixWorker.class));
        methods.forEach((method, annotation) -> register(bean, beanName, method, annotation));
        return bean;
    }

    /** Handlers by topic, in registration order. */
    public synchronized Map<String, JobHandler> handlers() {
        return Map.copyOf(handlers);
    }

    private synchronized void register(Object bean, String beanName, Method method, OrqixWorker annotation) {
        String topic = annotation.topic();
        String where = beanName + "." + method.getName();
        if (topic.isEmpty()) {
            throw new IllegalStateException("@OrqixWorker on " + where + " needs a topic");
        }
        if (owners.containsKey(topic)) {
            throw new IllegalStateException("Topic " + topic + " is served by both " + owners.get(topic) + " and "
                    + where);
        }
        if (method.getParameterCount() > 1 || method.getParameterCount() == 1
                && !(method.getParameterTypes()[0] == Job.class || method.getParameterTypes()[0] == Map.class)) {
            throw new IllegalStateException("@OrqixWorker method " + where + " takes no parameter, a Job or a "
                    + "Map<String, Object> of the input");
        }
        Class<?> returns = method.getReturnType();
        if (!(returns == void.class || Map.class.isAssignableFrom(returns))) {
            throw new IllegalStateException("@OrqixWorker method " + where + " returns a Map of the result fields "
                    + "or void");
        }
        Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        ReflectionUtils.makeAccessible(invocable);
        owners.put(topic, where);
        handlers.put(topic, job -> invoke(bean, invocable, job));
    }

    private static Map<String, ?> invoke(Object bean, Method method, Job job) throws Exception {
        Object[] args = switch (method.getParameterCount()) {
            case 0 -> new Object[0];
            default -> new Object[] {method.getParameterTypes()[0] == Job.class ? job : job.input()};
        };
        try {
            Object result = method.invoke(bean, args);
            @SuppressWarnings("unchecked")
            Map<String, ?> values = result == null ? Map.of() : (Map<String, ?>) result;
            return values;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception; // keeps BusinessErrorException and JobFailedException as they are
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}

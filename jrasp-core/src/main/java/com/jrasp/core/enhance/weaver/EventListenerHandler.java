package com.jrasp.core.enhance.weaver;

import com.jrasp.api.ProcessControlException;
import com.jrasp.api.event.BeforeEvent;
import com.jrasp.api.event.Event;
import com.jrasp.api.event.InvokeEvent;
import com.jrasp.api.listener.EventListener;
import com.jrasp.api.log.Log;
import com.jrasp.core.classloader.BusinessClassLoaderHolder;
import com.jrasp.core.log.LogFactory;
import com.jrasp.core.util.ObjectIDs;
import com.jrasp.core.util.RaspProtector;

import java.com.jrasp.spy.Spy;
import java.com.jrasp.spy.SpyHandler;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jrasp.api.event.Event.Type.IMMEDIATELY_RETURN;
import static com.jrasp.api.event.Event.Type.IMMEDIATELY_THROWS;
import static com.jrasp.core.log.AgentLogIdConstant.*;
import static com.jrasp.core.util.RaspReflectUtils.isInterruptEventHandler;
import static java.com.jrasp.spy.Spy.Ret.newInstanceForNone;
import static java.com.jrasp.spy.Spy.Ret.newInstanceForThrows;

import static org.apache.commons.lang3.ArrayUtils.contains;
import static org.apache.commons.lang3.StringUtils.join;

/**
 * 事件处理
 */
public class EventListenerHandler implements SpyHandler {

    private final static Log logger = LogFactory.getLog(EventListenerHandler.class);

    private static EventListenerHandler singleton = new EventListenerHandler();

    public static EventListenerHandler getSingleton() {
        return singleton;
    }

    // 调用序列生成器
    private final AtomicInteger invokeIdSequencer = new AtomicInteger(1000); // 不会溢出，回到最小值 -2147483648

    // 全局处理器ID:处理器映射集合
    public final Map<Integer/*LISTENER_ID*/, EventProcessor> mappingOfEventProcessor
            = new ConcurrentHashMap<Integer, EventProcessor>();

    /**
     * 注册事件处理器
     *
     * @param listenerId 事件监听器ID
     * @param listener   事件监听器
     * @param eventTypes 监听事件集合
     */
    public void active(final int listenerId,
                       final EventListener listener,
                       final Event.Type[] eventTypes) {
        mappingOfEventProcessor.put(listenerId, new EventProcessor(listenerId, listener, eventTypes));
        logger.info(ACTIVED_LISTENER_LOG_ID, "activated listener[id={};target={};] event={}",
                listenerId,
                listener,
                join(eventTypes, ",")
        );
    }

    /**
     * 取消事件处理器
     *
     * @param listenerId 事件处理器ID
     */
    public void frozen(int listenerId) {
        final EventProcessor processor = mappingOfEventProcessor.get(listenerId);
        if (null == processor) {
            return;
        }

        processor.frozen();

        logger.info(FROZEN_LISTENER_LOG_ID,"frozen listener[id={};target={};]",
                listenerId,
                processor.listener
        );

        // processor.clean();
    }

    /**
     * 移除事件处理器
     *
     * @param listenerId 事件处理器ID
     */
    public void remove(int listenerId) {
        final EventProcessor processor = mappingOfEventProcessor.remove(listenerId);
        if (null == processor) {
            return;
        }

        logger.info(REMOVE_LISTENER_LOG_ID,"remove listener[id={};target={};]",
                listenerId,
                processor.listener
        );
    }

    /**
     * 移除事件处理器
     *
     * @param listenerId 事件处理器ID
     */
    public EventProcessor get(int listenerId) {
        final EventProcessor processor = mappingOfEventProcessor.get(listenerId);
        return processor;
    }

    /**
     * 做一些必要的清理工作
     */
    public void clean() {
        for (Entry<Integer/*LISTENER_ID*/, EventProcessor> entry : mappingOfEventProcessor.entrySet()) {
            EventProcessor processor = entry.getValue();
            if (processor.isFrozen()) {
                processor.cleanThreadLocal();
            }
        }
        this.mappingOfEventProcessor.clear();
    }

    /**
     * 调用出发事件处理&调用执行流程控制
     *
     * @param listenerId 处理器ID
     * @param processId  调用过程ID
     * @param invokeId   调用ID
     * @param event      调用事件
     * @param processor  事件处理器
     * @return 处理返回结果
     * @throws Throwable 当出现未知异常时,且事件处理器为中断流程事件时抛出
     */
    private Spy.Ret handleEvent(final int listenerId,
                                final int processId,
                                final int invokeId,
                                final Event event,
                                final EventProcessor processor) throws Throwable {
        // 获取事件监听器
        final EventListener listener = processor.listener;

        // 如果当前事件不在事件监听器处理列表中，则直接返回，不处理事件
        if (!contains(processor.eventTypes, event.type)) {
            return newInstanceForNone();
        }

        // 调用事件处理
        try {
            listener.onEvent(event);
        }

        // 代码执行流程变更
        catch (ProcessControlException pce) {

            final EventProcessor.Process process = processor.processRef.get();

            final ProcessControlException.State state = pce.getState();

            // 如果流程控制要求忽略后续处理所有事件，则需要在此处进行标记
            if (pce.isIgnoreProcessEvent()) {
                process.markIgnoreProcess();
            }
            try {
                switch (state) {

                    // 立即返回对象
                    case RETURN_IMMEDIATELY: {

                        // 如果已经禁止后续返回任何事件了，则不进行后续的操作
                        if (pce.isIgnoreProcessEvent()) {

                        } else {
                            // 补偿立即返回事件
                            compensateProcessControlEvent(pce, processor, process, event);
                        }

                        // 如果是在BEFORE中立即返回，则后续不会再有RETURN事件产生
                        // 这里需要主动对齐堆栈
                        if (event.type == Event.Type.BEFORE) {
                            process.popInvokeId();
                        }

                        // 让流程立即返回
                        return Spy.Ret.newInstanceForReturn(pce.getRespond());

                    }

                    // 立即抛出异常
                    case THROWS_IMMEDIATELY: {

                        final Throwable throwable = (Throwable) pce.getRespond();

                        // 如果已经禁止后续返回任何事件了，则不进行后续的操作
                        if (pce.isIgnoreProcessEvent()) {

                        } else {

                            // 如果是在BEFORE中立即抛出，则后续不会再有THROWS事件产生
                            // 这里需要主动对齐堆栈
                            if (event.type == Event.Type.BEFORE) {
                                process.popInvokeId();
                            }

                            // 标记本次异常由ImmediatelyException产生，让下次异常事件处理直接忽略
                            if (event.type != Event.Type.THROWS) {
                                process.markExceptionFromImmediately();
                            }

                            // 补偿立即抛出事件
                            compensateProcessControlEvent(pce, processor, process, event);
                        }

                        // 让流程立即抛出
                        return Spy.Ret.newInstanceForThrows(throwable);

                    }

                    // 什么都不操作，立即返回
                    case NONE_IMMEDIATELY:
                    default: {
                        return newInstanceForNone();
                    }
                }
            } finally {
                if (process.isEmptyStack()) {
                    processor.clean();
                }
            }
        }

        // BEFORE处理异常,打日志,并通知下游不需要进行处理
        catch (Throwable throwable) {

            // 如果当前事件处理器是可中断的事件处理器,则对外抛出UnCaughtException
            // 中断当前方法
            if (isInterruptEventHandler(listener.getClass())) {
                throw throwable;
            }
            // 普通事件处理器则可以打个日志后,直接放行
        }

        // 默认返回不进行任何流程变更
        return newInstanceForNone();
    }

    // 补偿事件
    // 随着历史版本的演进，一些事件已经过期，但为了兼容API，需要在这里进行补偿
    private void compensateProcessControlEvent(ProcessControlException pce, EventProcessor processor, EventProcessor.Process process, Event event) {

        // 核对是否需要补偿，如果目标监听器没监听过这类事件，则不需要进行补偿
        if (!(event instanceof InvokeEvent)
                || !contains(processor.eventTypes, event.type)) {
            return;
        }

        final InvokeEvent iEvent = (InvokeEvent) event;
        final Event compensateEvent;

        // 补偿立即返回事件
        if (pce.getState() == ProcessControlException.State.RETURN_IMMEDIATELY
                && contains(processor.eventTypes, IMMEDIATELY_RETURN)) {
            compensateEvent = process
                    .getEventFactory()
                    .makeImmediatelyReturnEvent(iEvent.processId, iEvent.invokeId, pce.getRespond());
        }

        // 补偿立即抛出事件
        else if (pce.getState() == ProcessControlException.State.THROWS_IMMEDIATELY
                && contains(processor.eventTypes, IMMEDIATELY_THROWS)) {
            compensateEvent = process
                    .getEventFactory()
                    .makeImmediatelyThrowsEvent(iEvent.processId, iEvent.invokeId, (Throwable) pce.getRespond());
        }

        // 异常情况不补偿
        else {
            return;
        }

        try {
            processor.listener.onEvent(compensateEvent);
        } catch (Throwable cause) {
            logger.warn(PROCESS_EVENT_ERROR_LOG_ID,"compensate-event: event|{}|{}|{}|{} when ori-event:{} occur error.",
                    compensateEvent.type,
                    iEvent.processId,
                    iEvent.invokeId,
                    processor.listenerId,
                    event.type,
                    cause
            );
        } finally {
            process.getEventFactory().returnEvent(compensateEvent);
        }
    }

    /*
     * 判断堆栈是否错位
     */
    private boolean checkProcessStack(final int processId,
                                      final int invokeId,
                                      final boolean isEmptyStack) {
        return (processId == invokeId && !isEmptyStack)
                || (processId != invokeId && isEmptyStack);
    }

    @Override
    public Spy.Ret handleOnBefore(int listenerId, int targetClassLoaderObjectID, Object[] argumentArray, String javaClassName, String javaMethodName, String javaMethodDesc, Object target) throws Throwable {

        // 在守护区内产生的事件不需要响应
        if (RaspProtector.instance.isInProtecting()) {
            return newInstanceForNone();
        }

        // 获取事件处理器
        final EventProcessor processor = mappingOfEventProcessor.get(listenerId);

        // 如果尚未注册,则直接返回,不做任何处理
        if (null == processor) {
            return newInstanceForNone();
        }

        if (processor.isFrozen()) {
            return newInstanceForNone();
        }

        // 获取调用跟踪信息
        final EventProcessor.Process process = processor.processRef.get();

        // 如果当前处理ID被忽略，则立即返回
        if (process.isIgnoreProcess()) {
            return newInstanceForNone();
        }

        // 调用ID
        final int invokeId = invokeIdSequencer.getAndIncrement();
        process.pushInvokeId(invokeId);

        // 调用过程ID
        final int processId = process.getProcessId();

        final ClassLoader javaClassLoader = ObjectIDs.instance.getObject(targetClassLoaderObjectID);
        //放置业务类加载器
        BusinessClassLoaderHolder.setBussinessClassLoader(javaClassLoader);
        final BeforeEvent event = process.getEventFactory().makeBeforeEvent(
                processId,
                invokeId,
                javaClassLoader,
                javaClassName,
                javaMethodName,
                javaMethodDesc,
                target,
                argumentArray
        );
        try {
            return handleEvent(listenerId, processId, invokeId, event, processor);
        } finally {
            process.getEventFactory().returnEvent(event);
        }
    }

    @Override
    public Spy.Ret handleOnThrows(int listenerId, Throwable throwable) throws Throwable {
        try {
            return handleOnEnd(listenerId, throwable, false);
        } finally {
            BusinessClassLoaderHolder.removeBussinessClassLoader();
        }
    }

    @Override
    public Spy.Ret handleOnReturn(int listenerId, Object object) throws Throwable {
        try {
            return handleOnEnd(listenerId, object, true);
        } finally {
            BusinessClassLoaderHolder.removeBussinessClassLoader();
        }
    }


    private Spy.Ret handleOnEnd(final int listenerId,
                                final Object object,
                                final boolean isReturn) throws Throwable {

        // 在守护区内产生的事件不需要响应
        if (RaspProtector.instance.isInProtecting()) {
            return newInstanceForNone();
        }

        final EventProcessor wrap = mappingOfEventProcessor.get(listenerId);

        // 如果尚未注册,则直接返回,不做任何处理
        if (null == wrap) {
            return newInstanceForNone();
        }

        if (wrap.isFrozen()) {
            return newInstanceForNone();
        }

        final EventProcessor.Process process = wrap.processRef.get();

        // 如果当前调用过程信息堆栈是空的,说明
        // 1. BEFORE/RETURN错位
        // 2. super.<init>
        // 处理方式是直接返回,不做任何事件的处理和代码流程的改变,放弃对super.<init>的观察，可惜了
        if (process.isEmptyStack()) {
            wrap.processRef.remove(); // 清除上面创建的 process
            return newInstanceForNone();
        }

        // 如果异常来自于ImmediatelyException，则忽略处理直接返回抛异常
        final boolean isExceptionFromImmediately = !isReturn && process.rollingIsExceptionFromImmediately();
        if (isExceptionFromImmediately) {
            return newInstanceForThrows((Throwable) object);
        }

        // 继续异常处理
        final int processId = process.getProcessId();
        final int invokeId = process.popInvokeId();

        // 忽略事件处理
        // 放在stack.pop()后边是为了对齐执行栈
        if (process.isIgnoreProcess()) {
            return newInstanceForNone();
        }

        // 如果PID==IID说明已经到栈顶，此时需要核对堆栈是否为空
        // 如果不为空需要输出日志进行告警
        if (checkProcessStack(processId, invokeId, process.isEmptyStack())) {
            logger.warn(PROCESS_STACK_ERROR_LOG_ID,"ERROR process-stack. pid={};iid={};listener={};",
                    processId,
                    invokeId,
                    listenerId
            );
        }

        final Event event = isReturn
                ? process.getEventFactory().makeReturnEvent(processId, invokeId, object)
                : process.getEventFactory().makeThrowsEvent(processId, invokeId, (Throwable) object);

        try {
            return handleEvent(listenerId, processId, invokeId, event, wrap);
        } finally {
            process.getEventFactory().returnEvent(event);
        }

    }


    @Override
    public void handleOnCallBefore(int listenerId, int lineNumber, String owner, String name, String desc) throws Throwable {

        // 在守护区内产生的事件不需要响应
        if (RaspProtector.instance.isInProtecting()) {
            return;
        }

        final EventProcessor wrap = mappingOfEventProcessor.get(listenerId);
        if (null == wrap) {
            return;
        }

        if (wrap.isFrozen()) {
            return;
        }

        final EventProcessor.Process process = wrap.processRef.get();

        // 如果当前调用过程信息堆栈是空的,有两种情况
        // 1. CALL_BEFORE事件和BEFORE事件错位
        // 2. 当前方法是<init>，而CALL_BEFORE事件触发是当前方法在调用父类的<init>
        //    super.<init>会导致CALL_BEFORE事件优先于BEFORE事件
        // 但如果按照现在的架构要兼容这种情况，比较麻烦，所以暂时先放弃了这部分的消息，可惜可惜
        if (process.isEmptyStack()) {
            return;
        }

        final int processId = process.getProcessId();
        final int invokeId = process.getInvokeId();

        // 如果事件处理流被忽略，则直接返回，不产生后续事件
        if (process.isIgnoreProcess()) {
            return;
        }

        final Event event = process
                .getEventFactory()
                .makeCallBeforeEvent(processId, invokeId, lineNumber, owner, name, desc);
        try {
            handleEvent(listenerId, processId, invokeId, event, wrap);
        } finally {
            process.getEventFactory().returnEvent(event);
        }
    }

    @Override
    public void handleOnCallReturn(int listenerId) throws Throwable {

        // 在守护区内产生的事件不需要响应
        if (RaspProtector.instance.isInProtecting()) {
            return;
        }

        final EventProcessor wrap = mappingOfEventProcessor.get(listenerId);
        if (null == wrap) {
            return;
        }

        if (wrap.isFrozen()) {
            return;
        }

        final EventProcessor.Process process = wrap.processRef.get();
        if (process.isEmptyStack()) {
            return;
        }

        final int processId = process.getProcessId();
        final int invokeId = process.getInvokeId();

        // 如果事件处理流被忽略，则直接返回，不产生后续事件
        if (process.isIgnoreProcess()) {
            return;
        }

        final Event event = process
                .getEventFactory()
                .makeCallReturnEvent(processId, invokeId);
        try {
            handleEvent(listenerId, processId, invokeId, event, wrap);
        } finally {
            process.getEventFactory().returnEvent(event);
        }
    }

    @Override
    public void handleOnCallThrows(int listenerId, String throwException) throws Throwable {

        // 在守护区内产生的事件不需要响应
        if (RaspProtector.instance.isInProtecting()) {
            return;
        }

        final EventProcessor wrap = mappingOfEventProcessor.get(listenerId);
        if (null == wrap) {
            return;
        }

        if (wrap.isFrozen()) {
            return;
        }

        final EventProcessor.Process process = wrap.processRef.get();
        if (process.isEmptyStack()) {
            return;
        }

        final int processId = process.getProcessId();
        final int invokeId = process.getInvokeId();

        // 如果事件处理流被忽略，则直接返回，不产生后续事件
        if (process.isIgnoreProcess()) {
            return;
        }

        final Event event = process
                .getEventFactory()
                .makeCallThrowsEvent(processId, invokeId, throwException);
        try {
            handleEvent(listenerId, processId, invokeId, event, wrap);
        } finally {
            process.getEventFactory().returnEvent(event);
        }
    }

    @Override
    public void handleOnLine(int listenerId, int lineNumber) throws Throwable {

        // 在守护区内产生的事件不需要响应
        if (RaspProtector.instance.isInProtecting()) {
            return;
        }

        final EventProcessor wrap = mappingOfEventProcessor.get(listenerId);
        if (null == wrap) {
            return;
        }

        if (wrap.isFrozen()) {
            return;
        }

        final EventProcessor.Process process = wrap.processRef.get();

        // 如果当前调用过程信息堆栈是空的,说明BEFORE/LINE错位
        // 处理方式是直接返回,不做任何事件的处理和代码流程的改变
        if (process.isEmptyStack()) {
            return;
        }

        final int processId = process.getProcessId();
        final int invokeId = process.getInvokeId();

        // 如果事件处理流被忽略，则直接返回，不产生后续事件
        if (process.isIgnoreProcess()) {
            return;
        }

        final Event event = process.getEventFactory().makeLineEvent(processId, invokeId, lineNumber);
        try {
            handleEvent(listenerId, processId, invokeId, event, wrap);
        } finally {
            process.getEventFactory().returnEvent(event);
        }
    }

}

package com.taobao.arthas.core.command.monitor200;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.core.advisor.Advice;
import com.taobao.arthas.core.advisor.ArthasMethod;
import com.taobao.arthas.core.advisor.AdviceListenerAdapter;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.ThreadLocalWatch;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author ralf0131 2017-01-06 16:02.
 */
public class AbstractTraceAdviceListener extends AdviceListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(AbstractTraceAdviceListener.class);
    protected final ThreadLocalWatch threadLocalWatch = new ThreadLocalWatch();
    protected TraceCommand command;
    protected CommandProcess process;

    //转换增强字节码回调方法中的非标准类名
    //normalizeClassName:  a/b/c/MyClass -> a.b.c.MyClass
    protected Map<String, String> normalizeClassNameMap = new ConcurrentHashMap<String, String>();

    protected final ThreadLocal<TraceEntity> threadBoundEntity = new ThreadLocal<TraceEntity>() {

        @Override
        protected TraceEntity initialValue() {
            return new TraceEntity(normalizeClassNameMap);
        }
    };

    /**
     * Constructor
     */
    public AbstractTraceAdviceListener(TraceCommand command, CommandProcess process) {
        this.command = command;
        this.process = process;
    }

    @Override
    public void destroy() {
        threadBoundEntity.remove();
    }

    @Override
    public void before(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args)
            throws Throwable {
        threadBoundEntity.get().tree.begin(clazz.getName(), method.getName());
        threadBoundEntity.get().deep++;
        // 开始计算本次方法调用耗时
        threadLocalWatch.start();
    }

    @Override
    public void afterReturning(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args,
                               Object returnObject) throws Throwable {
        threadBoundEntity.get().tree.end();
        final Advice advice = Advice.newForAfterRetuning(loader, clazz, method, target, args, returnObject);
        finishing(advice);
    }

    @Override
    public void afterThrowing(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args,
                              Throwable throwable) throws Throwable {
        int lineNumber = throwable.getStackTrace()[0].getLineNumber();
        threadBoundEntity.get().tree.end(throwable.getClass().getName(), lineNumber);
        final Advice advice = Advice.newForAfterThrowing(loader, clazz, method, target, args, throwable);
        finishing(advice);
    }

    public TraceCommand getCommand() {
        return command;
    }

    private void finishing(Advice advice) {
        // 本次调用的耗时
        double cost = threadLocalWatch.costInMillis();
        if (--threadBoundEntity.get().deep == 0) {
            try {
                if (isConditionMet(command.getConditionExpress(), advice, cost)) {
                    // 满足输出条件
                    process.times().incrementAndGet();
                    // TODO: concurrency issues for process.write
                    process.appendResult(threadBoundEntity.get().getModel());

                    // 是否到达数量限制
                    if (isLimitExceeded(command.getNumberOfLimit(), process.times().get())) {
                        // TODO: concurrency issue to abort process
                        abortProcess(process, command.getNumberOfLimit());
                    }
                }
            } catch (Throwable e) {
                logger.warn("trace failed.", e);
                process.end(1, "trace failed, condition is: " + command.getConditionExpress() + ", " + e.getMessage()
                              + ", visit " + LogUtil.loggingFile() + " for more details.");
            } finally {
                threadBoundEntity.remove();
            }
        }
    }
}

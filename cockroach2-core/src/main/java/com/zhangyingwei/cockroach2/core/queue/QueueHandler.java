package com.zhangyingwei.cockroach2.core.queue;

import com.zhangyingwei.cockroach2.common.Constants;
import com.zhangyingwei.cockroach2.common.Task;
import com.zhangyingwei.cockroach2.common.async.AsyncUtils;
import com.zhangyingwei.cockroach2.common.enmus.TaskStatu;
import com.zhangyingwei.cockroach2.core.listener.QueueListener;
import com.zhangyingwei.cockroach2.core.queue.filter.ICQueueFilter;
import com.zhangyingwei.cockroach2.queue.ICQueue;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author zhangyw
 * @date: 2018/12/11
 * @desc:
 */

@Slf4j
public class QueueHandler implements ICQueue {
    private ICQueue queue;
    private Set<ICQueueFilter> filters = new HashSet<ICQueueFilter>();
    @Getter
    private Boolean withBlock = false;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private Long limitSize = -1L;
    private final QueueListener listener = new QueueListener();

    private QueueHandler(ICQueue queue) {
        this.queue = queue;
    }

    public static QueueHandler initWithQueue(ICQueue queue) {
        return new QueueHandler(queue);
    }

    public static QueueHandler initWithDefaultQueue(int calacify) {
        return new QueueHandler(new TaskQueue(calacify));
    }

    public static QueueHandler initWithDefaultQueue() {
        return initWithDefaultQueue(Constants.QUEUE_SIZE);
    }

    public QueueHandler withFilter(ICQueueFilter filter) {
        this.filters.add(filter);
        return this;
    }

    public QueueHandler limit(Long limit) {
        this.limitSize = limit;
        return this;
    }

    public QueueHandler withBlock(Boolean block) {
        this.withBlock = block;
        return this;
    }

    @Override
    public Task get() {
        try {
            this.lock.lock();
            Task task = queue.get();
            if (task != null) {
                task.statu(TaskStatu.START).tryOne();
            }
            this.condition.signalAll();
            if (this.withBlock) {
                while (task == null && this.queue.isEmpty()) {
                    log.debug("queue get was blocked, size is: {}",queue.size());
                    this.condition.await();
                    task = queue.get();
                }
            } else {
                /**
                 * Variable used in lambda expression should be final or effectively final
                 * 因为 task 在上边被修改过一次，所以不能直接被传到 lambda 表达式中，因此需要下边这行代码
                 */
                Task finalTask = task;
                AsyncUtils.doVoidMethodAsync(() -> listener.get(finalTask));
                return task;
            }
            Task finalTask1 = task;
            AsyncUtils.doVoidMethodAsync(() -> listener.get(finalTask1));
            return task;
        } catch (InterruptedException e) {
            log.error("get task from queue error: {}", e.getLocalizedMessage());
        } finally {
            this.lock.unlock();
        }
        return null;
    }

    @Override
    public void add(Task task) {
        try {
            this.lock.lock();
            Boolean accept = true;
            for (ICQueueFilter filter : filters) {
                if (!filter.accept(task)) {
                    accept = false;
                    log.info("task is not accept by {}", filter.getClass());
                    break;
                }
            }
            if (accept) {
                if (this.limitSize > 0) {
                    if (this.queue.size() <= this.limitSize) {
                        this.queue.add(task);
                        AsyncUtils.doVoidMethodAsync(() -> listener.add(task));
                        this.condition.signalAll();
                    } else {
                        log.debug("queue add was blocked, size is: {}",queue.size());
                        this.condition.await();
                    }
                } else {
                    this.queue.add(task);
                    AsyncUtils.doVoidMethodAsync(() -> listener.add(task));
                }
            }
        } catch (InterruptedException e) {
            log.error("add task to queue error: {}", e.getLocalizedMessage());
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int size() {
        return this.queue.size();
    }

    @Override
    public boolean isEmpty() {
        return this.queue.isEmpty();
    }
}

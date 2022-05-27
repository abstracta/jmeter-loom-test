package us.abstracta.jmeter.javadsl.core.threadgroups;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.engine.TreeCloner;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.gui.ThreadGroupGui;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.util.JMeterStopTestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DSL Thread group that uses virtual threads instead of standard ones.
 */
public class VirtualThreadGroup extends BaseThreadGroup<VirtualThreadGroup> {

  private final int threads;
  private final int iterations;
  private final Duration duration;

  public VirtualThreadGroup(int threads, int iterations,
      Duration duration, List<ThreadGroupChild> children) {
    super("Virtual Thread Group", ThreadGroupGui.class, children);
    this.threads = threads;
    this.iterations = iterations;
    this.duration = duration;
  }

  public static VirtualThreadGroup virtualThreadGroup(int threads, int iterations,
      ThreadGroupChild... children) {
    return new VirtualThreadGroup(threads, iterations, null, Arrays.asList(children));
  }

  public static VirtualThreadGroup virtualThreadGroup(int threads, Duration duration,
      ThreadGroupChild... children) {
    return new VirtualThreadGroup(threads, 0, duration, Arrays.asList(children));
  }

  public AbstractThreadGroup buildThreadGroup() {
    VirtualThreadGroupTestElement ret = new VirtualThreadGroupTestElement();
    ret.setNumThreads(threads);
    LoopController loopController = new LoopController();
    ret.setSamplerController(loopController);
    if (duration != null) {
      loopController.setLoops(-1);
      ret.setDuration(durationToSeconds(duration));
    } else {
      loopController.setLoops(iterations);
    }
    if (duration != null) {
      ret.setScheduler(true);
    }
    return ret;
  }

  /**
   * JMeter thread group that uses virtual threads.
   * <p>
   * This code is mostly a copy of JMeter {@link ThreadGroup} class but modifying it to use
   * virtual threads and to remove logic that is not necessary for the test (ramp ups &amp; delays).
   */
  public static class VirtualThreadGroupTestElement extends AbstractThreadGroup {

    private static final Logger LOG = LoggerFactory.getLogger(VirtualThreadGroupTestElement.class);

    private static final long WAIT_TO_DIE = JMeterUtils
        .getPropDefault("jmeterengine.threadstop.wait", 5 * 1000); // 5 seconds

    private final ConcurrentHashMap<JMeterThread, Thread> allThreads = new ConcurrentHashMap<>();
    // This is one of the lines we changed to use ReentrantLock instead of Object
    private final transient Lock addThreadLock = new ReentrantLock();
    private volatile boolean running = false;
    private int groupNumber;
    private ListenerNotifier notifier;
    private ListedHashTree threadGroupTree;

    public long getDuration() {
      return getPropertyAsLong(ThreadGroup.DURATION);
    }

    public void setDuration(long duration) {
      setProperty(new LongProperty(ThreadGroup.DURATION, duration));
    }

    public void setScheduler(boolean scheduler) {
      setProperty(new BooleanProperty(ThreadGroup.SCHEDULER, scheduler));
    }

    public boolean getScheduler() {
      return getPropertyAsBoolean(ThreadGroup.SCHEDULER);
    }

    @Override
    public void start(int groupNum, ListenerNotifier notifier, ListedHashTree threadGroupTree,
        StandardJMeterEngine engine) {
      this.running = true;
      this.groupNumber = groupNum;
      this.notifier = notifier;
      this.threadGroupTree = threadGroupTree;
      int numThreads = getNumThreads();
      boolean isSameUserOnNextIteration = isSameUserOnNextIteration();
      LOG.info("Starting thread group... number={} threads={}", groupNumber, numThreads);
      final JMeterContext context = JMeterContextService.getContext();
      for (int threadNum = 0; running && threadNum < numThreads; threadNum++) {
        startNewThread(notifier, threadGroupTree, engine, threadNum, context,
            System.currentTimeMillis(), isSameUserOnNextIteration);
      }
      LOG.info("Started thread group number {}", groupNumber);
    }

    private JMeterThread startNewThread(ListenerNotifier notifier, ListedHashTree threadGroupTree,
        StandardJMeterEngine engine, int threadNum, final JMeterContext context, long now,
        Boolean isSameUserOnNextIteration) {
      JMeterThread jmThread = makeThread(notifier, threadGroupTree, engine, threadNum, context,
          isSameUserOnNextIteration);
      scheduleThread(jmThread, now);
      jmThread.setInitialDelay(0);
      // This is the main change introduced to thread group to use virtual threads
      Thread newThread = Thread.ofVirtual()
          .name(jmThread.getThreadName())
          .unstarted(jmThread);
      registerStartedThread(jmThread, newThread);
      newThread.start();
      return jmThread;
    }

    private JMeterThread makeThread(
        ListenerNotifier notifier, ListedHashTree threadGroupTree,
        StandardJMeterEngine engine, int threadNumber,
        JMeterContext context,
        Boolean isSameUserOnNextIteration) {
      final JMeterThread jmeterThread = new JMeterThread(cloneTree(threadGroupTree), this, notifier,
          isSameUserOnNextIteration);
      jmeterThread.setThreadNum(threadNumber);
      jmeterThread.setThreadGroup(this);
      jmeterThread.setInitialContext(context);
      String distributedPrefix =
          JMeterUtils
              .getPropDefault(JMeterUtils.THREAD_GROUP_DISTRIBUTED_PREFIX_PROPERTY_NAME, "");
      final String threadName =
          distributedPrefix + (distributedPrefix.isEmpty() ? "" : "-") + getName() + " "
              + groupNumber + "-" + (threadNumber + 1);
      jmeterThread.setThreadName(threadName);
      jmeterThread.setEngine(engine);
      jmeterThread.setOnErrorStopTest(getOnErrorStopTest());
      jmeterThread.setOnErrorStopTestNow(getOnErrorStopTestNow());
      jmeterThread.setOnErrorStopThread(getOnErrorStopThread());
      jmeterThread.setOnErrorStartNextLoop(getOnErrorStartNextLoop());
      return jmeterThread;
    }

    private ListedHashTree cloneTree(ListedHashTree tree) {
      TreeCloner cloner = new TreeCloner(true);
      tree.traverse(cloner);
      return cloner.getClonedTree();
    }

    private void scheduleThread(JMeterThread thread, long now) {
      if (!getScheduler()) {
        return;
      }
      thread.setStartTime(now);
      if (getDuration() > 0) {
        thread.setEndTime(getDuration() * 1000 + thread.getStartTime());
      } else {
        throw new JMeterStopTestException(
            "Invalid duration " + getDuration() + " set in Thread Group:" + getName());
      }
      thread.setScheduled(true);
    }

    private void registerStartedThread(JMeterThread jMeterThread, Thread newThread) {
      allThreads.put(jMeterThread, newThread);
    }

    @Override
    public JMeterThread addNewThread(int delay, StandardJMeterEngine engine) {
      int numThreads;
      // Here we changed from synchronized block to reentrant lock usage
      addThreadLock.lock();
      try {
        numThreads = getNumThreads();
        setNumThreads(numThreads + 1);
      } finally {
        addThreadLock.unlock();
      }
      JMeterThread newJmThread = startNewThread(notifier, threadGroupTree, engine, numThreads,
          JMeterContextService.getContext(), System.currentTimeMillis(),
          isSameUserOnNextIteration());
      JMeterContextService.addTotalThreads(1);
      LOG.info("Started new thread in group {}", groupNumber);
      return newJmThread;
    }

    @Override
    public boolean stopThread(String threadName, boolean now) {
      for (Map.Entry<JMeterThread, Thread> threadEntry : allThreads.entrySet()) {
        JMeterThread jMeterThread = threadEntry.getKey();
        if (jMeterThread.getThreadName().equals(threadName)) {
          stopThread(jMeterThread, threadEntry.getValue(), now);
          return true;
        }
      }
      return false;
    }

    private void stopThread(JMeterThread jmeterThread, Thread jvmThread, boolean interrupt) {
      jmeterThread.stop();
      jmeterThread.interrupt();
      if (interrupt && jvmThread != null) {
        jvmThread.interrupt();
      }
    }

    @Override
    public void threadFinished(JMeterThread thread) {
      LOG.debug("Ending thread {}", thread.getThreadName());
      allThreads.remove(thread);
    }

    @Override
    public void tellThreadsToStop() {
      running = false;
      allThreads.forEach((key, value) -> stopThread(key, value, true));
    }

    @Override
    public void stop() {
      running = false;
      allThreads.keySet().forEach(JMeterThread::stop);
    }

    @Override
    public int numberOfActiveThreads() {
      return allThreads.size();
    }

    @Override
    public boolean verifyThreadsStopped() {
      for (Thread t : allThreads.values()) {
        if (!verifyThreadStopped(t)) {
          return false;
        }
      }
      return true;
    }

    private boolean verifyThreadStopped(Thread thread) {
      boolean stopped = true;
      if (thread != null && thread.isAlive()) {
        try {
          thread.join(WAIT_TO_DIE);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
          stopped = false;
          LOG.warn("Thread won't exit: {}", thread.getName());
        }
      }
      return stopped;
    }

    @Override
    public void waitThreadsStopped() {
      while (!allThreads.isEmpty()) {
        allThreads.values().forEach(this::waitThreadStopped);
      }
    }

    private void waitThreadStopped(Thread thread) {
      if (thread == null) {
        return;
      }
      while (thread.isAlive()) {
        try {
          thread.join(WAIT_TO_DIE);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

  }


}

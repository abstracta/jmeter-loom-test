package us.abstracta.jmeter.javadsl.core.threadgroups;

import static us.abstracta.jmeter.javadsl.JmeterDsl.dummySampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jsr223PostProcessor;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;
import static us.abstracta.jmeter.javadsl.core.threadgroups.VirtualThreadGroup.virtualThreadGroup;

import java.time.Duration;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;
import us.abstracta.jmeter.javadsl.core.samplers.DslSampler;
import us.abstracta.jmeter.javadsl.core.stats.StatsSummary;
import us.abstracta.jmeter.javadsl.core.threadgroups.BaseThreadGroup.ThreadGroupChild;
import us.abstracta.jmeter.javadsl.http.DslHttpSampler.HttpClientImpl;

@Command(name = "jmeter-loom-test", mixinStandardHelpOptions = true,
    header = "Script to run simple HTTP tests to check loom differences with standard threads")
public class PerformanceTest implements Callable<Integer> {

  @Option(names = {"-t", "--threads"}, required = true, description = "Number of threads to use")
  private int threads;

  @Option(names = {"-it", "--iterations"},
      description = "Number of iterations each thread will execute", defaultValue = "1")
  private int iterations;

  @Option(names = {"-d", "--duration"},
      description = "Duration in seconds to run the test plan.")
  private int durationSeconds;

  @Option(names = {"-u", "--url"},
      description = "The URL to send requests to. If no URL is specified then a dummy sampler is"
          + " used.")
  private String url;

  @Option(names = {"-impl", "--implementation"}, required = true,
      description = "The type of thread to use. Valid values: ${COMPLETION-CANDIDATES}")
  private Implementation impl;

  public enum Implementation {
    DEFAULT,
    VIRTUAL
  }

  @Override
  public Integer call() throws Exception {
    TestPlanStats stats = testPlan(
        buildThreadGroup(buildSampler())
    ).run();
    StatsSummary overallStats = stats.overall();
    System.out.printf(
        "count: %d, avgTime: %d, perc99: %d, errCount: %d, tps:%.2f, sent:%.2f, rec:%.2f%n",
        overallStats.samplesCount(),
        overallStats.sampleTime().mean().toMillis(),
        overallStats.sampleTime().perc99().toMillis(),
        overallStats.errorsCount(),
        overallStats.samples().perSecond(),
        overallStats.sentBytes().perSecond(),
        overallStats.receivedBytes().perSecond());

    stats.labels().forEach(l ->
        System.out.println(l + ": " + stats.byLabel(l).samplesCount())
    );
    return 0;
  }

  private DslThreadGroup buildThreadGroup(ThreadGroupChild... children) {
    if (durationSeconds != 0) {
      return impl == Implementation.VIRTUAL
          ? virtualThreadGroup(threads, Duration.ofSeconds(durationSeconds), children)
          : threadGroup(threads, Duration.ofSeconds(durationSeconds), children);
    } else {
      return impl == Implementation.VIRTUAL
          ? virtualThreadGroup(threads, iterations, children)
          : threadGroup(threads, iterations, children);
    }
  }

  private DslSampler buildSampler() {
    return url != null
        ? httpSampler(url)
        .clientImpl(HttpClientImpl.JAVA)
        .children(
            jsr223PostProcessor(s -> {
              if (!s.prev.isSuccessful()) {
                s.prev.setSampleLabel(
                    s.prev.getResponseCode() + " - " + s.prev.getResponseMessage());
              }
            })
        )
        : dummySampler("test")
            .responseTime(Duration.ofSeconds(1))
            .simulateResponseTime(true);
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new PerformanceTest())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .execute(args);
    System.exit(exitCode);
  }

}
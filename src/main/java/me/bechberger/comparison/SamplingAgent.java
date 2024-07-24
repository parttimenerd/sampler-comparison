package me.bechberger.comparison;

import java.lang.Thread.State;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class SamplingAgent implements Runnable {

    private static void printHelp() {
        System.err.println("Usage: java -javaagent:SamplingAgent.jar=[interval=interval in ms,default 10][,file=..., default sample.txt][,depth=100]");
    }

    public static void agentmain(String agentArgs) {
        premain(agentArgs);
    }

    public static void premain(String agentArgs) {
        // two options interval and final file, comma separated
        int interval = 10;
        String file = "sample.txt";
        int depth = 100;
        String[] args = (agentArgs == null ? "" : agentArgs).split(",");
        for (String arg : args) {
            if (arg.isBlank()) {
                continue;
            }
            String[] parts = arg.split("=");
            if (parts.length != 2) {
                printHelp();
                return;
            }
            switch (parts[0]) {
                case "interval":
                    interval = Integer.parseInt(parts[1]);
                    break;
                case "file":
                    file = parts[1];
                    break;
                case "depth":
                    depth = Integer.parseInt(parts[1]);
                    break;
                default:
                    System.err.println("Unknown option: " + parts[0]);
                    printHelp();
                    return;
            }
        }
        start(interval, Path.of(file), depth);
    }

    public static final String SAMPLING_THREAD_NAME = "SamplingAgent";

    private volatile boolean stop = false;
    private final int intervalMs;
    private final Path file;
    private final Store store;

    public SamplingAgent(int intervalMs, Path file, int maxDepth) {
        this.intervalMs = intervalMs;
        this.file = file;
        this.store = new Store("Thread.getStackTrace", maxDepth);
    }

    public static void start(int intervalMs, Path file, int maxDepth) {
        var sampler = new SamplingAgent(intervalMs, file, maxDepth);
        Runtime.getRuntime().addShutdownHook(new Thread(sampler::onEnd));
        var thread = new Thread(sampler, SAMPLING_THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    }

    private static void sleep(Duration duration) throws InterruptedException {
        if (duration.isNegative() || duration.isZero()) {
            return;
        }
        Thread.sleep(duration.toMillis(), duration.toNanosPart() % 1000000);
    }

    @Override
    public void run() {
        while (!stop) {
            var start = System.nanoTime();
            sample();
            var duration = System.nanoTime() - start;
            var sleep = intervalMs * 1000_000L - duration;
            try {
                sleep(Duration.ofNanos(sleep));
            } catch (InterruptedException e) {
                break;
            }
        }
        store.store(file);
	System.out.println();
        System.out.println(Store.intervalsToTable(List.of(store)));
        stop = false;
    }

    void sample() {
        Thread.getAllStackTraces().forEach((thread, stackTrace) -> {
            if (thread.getName().equals(SAMPLING_THREAD_NAME) && thread.getState() == State.RUNNABLE) {
                return;
            }
            store.add(thread.getName(), stackTrace, System.nanoTime());
        });
    }

    public void onEnd() {
        stop = true;
        while (stop) {
            Thread.onSpinWait();
        }
    }
}

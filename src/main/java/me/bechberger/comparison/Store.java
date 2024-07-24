package me.bechberger.comparison;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Stores stack traces and their hashes, write from disc and read from disc
 */
public class Store {

    public record TimeAndHash(long timeNanos, byte[] hash) {}

    private final String name;
    private final int maxDepth;
    private final Map<String, List<TimeAndHash>> dataPerThread;
    private final ThreadLocal<MessageDigest> digest = new ThreadLocal<>();

    public Store(String name, int maxDepth, Map<String, List<TimeAndHash>> dataPerThread) {
        this.name = name;
        this.maxDepth = maxDepth;
        this.dataPerThread = dataPerThread;
    }

    private MessageDigest getDigest() {
        if (digest.get() == null) {
            try {
                digest.set(MessageDigest.getInstance("SHA-256"));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("Could not create digest", e);
            }
        }
        return digest.get();
    }

    public Store(String name, int maxDepth) {
        this(name, maxDepth, new HashMap<>());
    }

    public String getName() {
        return this.name;
    }

    public Map<String, List<TimeAndHash>> getDataPerThread() {
        return this.dataPerThread;
    }

    private final Set<String> IGNORED_THREADS = Set.of(
            SamplingAgent.SAMPLING_THREAD_NAME,
            "Reference Handler",
            "Finalizer",
            "Signal Dispatcher",
            "Common-Cleaner",
            "JFR Periodic Tasks",
            "JFR Recorder Thread",
            "Notification Thread"
    );

    private boolean checkThreadName(String threadName) {
        return !IGNORED_THREADS.contains(threadName);
    }

    private void add(String threadName, long timeNanos, byte[] hash) {
        this.dataPerThread.computeIfAbsent(threadName, k -> new ArrayList<>()).add(new TimeAndHash(timeNanos, hash));
    }

    /**
     * Throw away addresses and normalize class names
     * <p>
     * E.g. from {@code java.lang.invoke.LambdaForm$DMH/0x0000007c01001000}
     * to {@code java.lang.invoke.LambdaForm$DMH}
     * @param className
     * @return
     */
    private String normalizeClassName(String className) { // throw away addresses
        className = className.split("[^a-zA-Z0-9_$.]")[0];
        return className.replaceAll("\\$\\$Lambda.*", "\\$\\$Lambda");
    }

    private <T> void add(String threadName, List<T> stackTrace, long timeNanos, Function<T, String> className, Function<T, String> methodName) {
        if (!checkThreadName(threadName)) {
            return;
        }
        if (stackTrace.size() < 2) {
            return;
        }
        getDigest().reset();
        for (int i = Math.max(0, stackTrace.size() - maxDepth); i < stackTrace.size(); i++) {
            String name = normalizeClassName(className.apply(stackTrace.get(i)));
            getDigest().update((name + "." + methodName.apply(stackTrace.get(i))).getBytes());
        }
        byte[] hash = getDigest().digest();
        this.add(threadName, timeNanos, hash);
    }

    public void add(String threadName, RecordedStackTrace stackTrace, long timeNanos) {
        add(threadName, stackTrace.getFrames(), timeNanos, f -> f.getMethod().getType().getName(), f -> f.getMethod().getName());
    }

    public void add(String threadName, StackTraceElement[] stackTrace, long timeNanos) {
        add(threadName, Arrays.asList(stackTrace), timeNanos, StackTraceElement::getClassName, StackTraceElement::getMethodName);
    }

    public void store(Path path) {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write(this.name);
            writer.newLine();
            writer.write(Integer.toString(this.maxDepth));
            writer.newLine();
            for (var entry : this.dataPerThread.entrySet()) {
                writer.write(Base64.getEncoder().encodeToString(entry.getKey().getBytes()));
                writer.newLine();
                for (var timeAndHash : entry.getValue()) {
                    // write time base64 encoded, by encoding the bit string
                    writer.write(Long.toUnsignedString(timeAndHash.timeNanos, Character.MAX_RADIX));
                    writer.write(" ");
                    writer.write(Base64.getEncoder().encodeToString(timeAndHash.hash));
                    writer.newLine();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not store data", e);
        }
    }

    public static List<Store> readJFR(Path path, int maxDepth) {
        return readJFR(path, maxDepth, null);
    }

    public static List<Store> readJFR(Path path, int maxDepth, String name) {
        try (RecordingFile recordingFile = new RecordingFile(path)) {
            var oldStore = new Store(name == null ? "Old sampler" : name, maxDepth);
            var newStore = name == null ? new Store("New sampler", maxDepth) : null;
            var newStoreWithErrors = name == null ? new Store("New with errors", maxDepth) : null;
            while (recordingFile.hasMoreEvents()) {
                var event = recordingFile.readEvent();
                var eventTypeName = event.getEventType().getName();
                var timeNanos = event.getStartTime().getEpochSecond() * 1_000_000_000 + event.getStartTime().getNano();
                if (eventTypeName.equals("jdk.ExecutionSample") || eventTypeName.equals("jdk.NativeMethodSample")) {
                    var stackTrace = event.getStackTrace();
                    if (event.getThread("sampledThread").getJavaName() == null) {
                        continue;
                    }
                    oldStore.add(event.getThread("sampledThread").getJavaName(), stackTrace, timeNanos);
                } else if (newStore != null && eventTypeName.equals("jdk.CPUTimeExecutionSample")) {
                    var stackTrace = event.getStackTrace();
                    var sampledThread = event.getThread("sampledThread").getJavaName();
                    if (stackTrace == null) {
                        newStoreWithErrors.add(sampledThread, timeNanos, new byte[]{(byte)0});
                        continue;
                    }
                    newStore.add(sampledThread, stackTrace, timeNanos);
                    newStoreWithErrors.add(sampledThread, stackTrace, timeNanos);
                }
            }
            return Stream.of(oldStore, newStore, newStoreWithErrors).filter(s -> s != null && !s.getDataPerThread().isEmpty()).collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException("Could not read JFR data", e);
        }
    }

    public static Store read(Path path) {
        if (path.endsWith(".jfr")) {
            throw new IllegalArgumentException("Use readJFR for JFR files");
        }
        try (var reader = Files.newBufferedReader(path)) {
            String name = reader.readLine();
            int maxDepth = Integer.parseInt(reader.readLine());
            Map<String, List<TimeAndHash>> dataPerThread = new HashMap<>();
            String curThread = null;
            List<TimeAndHash> cur = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length == 1) {
                    if (curThread != null) {
                        dataPerThread.put(curThread, cur);
                        cur = new ArrayList<>();
                    }
                    curThread = new String(Base64.getDecoder().decode(parts[0]));
                } else {
                    long time = Long.parseLong(parts[0], Character.MAX_RADIX);
                    byte[] hash = Base64.getDecoder().decode(parts[1]);
                    cur.add(new TimeAndHash(time, hash));
                }
            }
            if (curThread != null) {
                dataPerThread.put(curThread, cur);
            }
            return new Store(name, maxDepth, dataPerThread);
        } catch (Exception e) {
            throw new RuntimeException("Could not read data", e);
        }
    }

    public static class HashWrapper {
        private final byte[] hash;
        HashWrapper(byte[] hash) {
            this.hash = hash;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof HashWrapper && Arrays.equals(this.hash, ((HashWrapper) obj).hash);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.hash);
        }
    }

    public Map<HashWrapper, Integer> getAbsoluteHashDistribution() {
        Map<HashWrapper, Integer> result = new HashMap<>();
        for (var entry : this.dataPerThread.entrySet()) {
            for (var timeAndHash : entry.getValue()) {
                var wrapped = new HashWrapper(timeAndHash.hash());
                result.put(wrapped, result.getOrDefault(wrapped, 0) + 1);
            }
        }
        return result;
    }

    public Map<HashWrapper, Float> getRelativeDistribution() {
        int sum = this.dataPerThread.values().stream().mapToInt(List::size).sum();
        return getAbsoluteHashDistribution().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue() / (float) sum));
    }

    public record PercResult(float summedDiff, float percThatIsntInOther) {}

    /**
     * Calculate the percentage of this stores' samples
     * that are not in the other store (either by not at all
     * or by appearing with a different frequency, take the percentage point
     * difference then)
     */
    public PercResult differencePercentagePoints(Store other) {
        if (this.maxDepth != other.maxDepth) {
            throw new IllegalArgumentException("Max depth does not match");
        }
        var percThatIsntInOther = 0f;
        var thisDistribution = this.getRelativeDistribution();
        var otherDistribution = other.getRelativeDistribution();
        float sum = 0;
        for (var entry : thisDistribution.entrySet()) {
            var diff = entry.getValue() - otherDistribution.getOrDefault(entry.getKey(), 0f);
            if (diff > 0) {
                sum += Math.abs(diff);
            }
            if (diff == entry.getValue()) {
                percThatIsntInOther += entry.getValue();
            }
        }
        /*for (var entry : otherDistribution.entrySet()) {
            if (!thisDistribution.containsKey(entry.getKey())) {
                sum += entry.getValue();
            }
        }*/
        return new PercResult(sum, percThatIsntInOther);
    }

    /**
     *
     * @param avgNs average interval in nanoseconds
     * @param stdDevNs std dev of this interval in nanoseconds
     * @param avgTrimmed average interval in nanoseconds without the 10% highest values
     * @param stdDevTrimmed std dev of avgTrimmed
     * @param minNs minimum interval in nanoseconds
     * @param ninetyPercNs 90th percentile of the interval in nanoseconds
     * @param maxNs maximum interval in nanoseconds
     */
    public record ComputedInterval(long samples, long avgNs, long stdDevNs, long avgTrimmed, long stdDevTrimmed, long minNs, long tenPercNs, long ninetyPercNs, long maxNs) {}

    public static String intervalsToTable(List<Store> stores) {
        var computedIntervals = stores.parallelStream().map(store -> Map.entry(store, store.computedInterval(10))).sorted(Comparator.comparing(e -> e.getKey().name)).toList();
        // print a table with the results, columns are 15 characters wide
        var header = String.format("%-20s%11s%11s%11s%11s%11s%11s%11s%11s%11s", "Name", "Samples", "Avg", "StdDev", "AvgTrimmed", "StdTrimmed", "Min", "10thPerc", "90thPerc", "Max");
        var separator = "-".repeat(header.length());
        // cells as ms with 3 decimal places
        var body = computedIntervals.stream().map(e -> String.format("%-20s%11d%11.3f%11.3f%11.3f%11.3f%11.3f%11.3f%11.3f%11.3f",
                e.getKey().name, e.getValue().samples, e.getValue().avgNs / 1_000_000f, e.getValue().stdDevNs / 1_000_000f, e.getValue().avgTrimmed / 1_000_000f, e.getValue().stdDevTrimmed / 1_000_000f, e.getValue().minNs / 1_000_000f, e.getValue().tenPercNs / 1_000_000f, e.getValue().ninetyPercNs / 1_000_000f, e.getValue().maxNs / 1_000_000f)).collect(Collectors.joining("\n"));
        return header + "\n" + separator + "\n" + body;
    }

    public ComputedInterval computedInterval(int minSamplesPerThread) {
        var sampleCount = this.dataPerThread.values().stream().mapToInt(List::size).sum();
        var diffs = computeTimeDiffs(minSamplesPerThread);
        diffs.sort(Comparator.naturalOrder());
        var ninetyPercNsIndex = (int) (diffs.size() * 0.9);
        var tenPercNsIndex = (int) (diffs.size() * 0.1);
        var trimmedDiffs = diffs.subList(tenPercNsIndex, ninetyPercNsIndex);
        var diffsStat = diffs.stream().mapToLong(i -> i).summaryStatistics();
        var trimmedStat = trimmedDiffs.stream().mapToLong(i -> i).summaryStatistics();
        return new ComputedInterval(sampleCount, (long) diffsStat.getAverage(), stddev(diffs, (long)diffsStat.getAverage()), (long) trimmedStat.getAverage(), stddev(trimmedDiffs, (long)trimmedStat.getAverage()), diffsStat.getMin(), diffs.get(tenPercNsIndex), diffs.get(ninetyPercNsIndex), diffsStat.getMax());
    }

    private long stddev(List<Long> diffs, long avg) {
        return (long) Math.sqrt(diffs.stream().mapToDouble(i -> Math.pow(i - avg, 2)).sum() / diffs.size());
    }

    private List<Long> computeTimeDiffs(int minSamplesPerThread) {
        List<Long> diffs = new ArrayList<>();
        for (var entry : this.dataPerThread.entrySet()) {
            if (entry.getValue().size() < minSamplesPerThread) {
                continue;
            }
            entry.getValue().sort(Comparator.comparingLong(TimeAndHash::timeNanos));
            var sorted = entry.getValue();
            for (int i = 1; i < sorted.size(); i++) {
                var diff = sorted.get(i).timeNanos() - sorted.get(i - 1).timeNanos();
                diffs.add(diff);
            }
        }
        return diffs;
    }

    public int getMaxDepth() {
        return maxDepth;
    }
}
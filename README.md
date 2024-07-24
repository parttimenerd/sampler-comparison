Sampler Comparison
==================

Compare different method samplers:

- JFR's standard sampler ("old sampler")
- The new CPU time sampler in JFR (see [my fork](https://github.com/parttimenerd/jdk/tree/parttimenerd_jfr_cpu_time_sampler3) for an implementation)
- A `Thread.getAllStackTraces()` based sampler (via the built-in agent), see [tiny-profiler](https://mostlynerdless.de/blog/2023/03/27/writing-a-profiler-in-240-lines-of-pure-java/)

It obtains two results:
1. A measure of the actual interval between samples, which should be close to 10ms in the default configuration
2. A measure of sample overlap

Build
-----
```shell
mvn package
```

Run
---
Obtain the samples
```shell
java -javaagent:target/sampler-comparison.jar=depth=10 -XX:StartFlightRecording=filename=profile.jfr,settings=custom.jfc -jar renaissance.jar all
```
The resulting `profile.jfr` and `sample.txt` files can be
analyzed via:
```shell
java -jar target/sampler-comparison.jar profile.jfr sample.txt
```

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors
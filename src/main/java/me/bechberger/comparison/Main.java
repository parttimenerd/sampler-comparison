package me.bechberger.comparison;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Command(name = "sampler-comparison", mixinStandardHelpOptions = true,
        description = "Comparing different samplers")
public class Main implements Runnable {

    @Parameters(index = "0..*", description = "JFR and sample files")
    private List<Path> files;

    @Override
    public void run() {
        var withoutJFR = files.stream().filter(file -> !file.toString().endsWith(".jfr")).findAny();
        int maxDepth = 100;
        List<Store> stores = new ArrayList<>();
        if (withoutJFR.isPresent()) {
            Store store = Store.read(withoutJFR.get());
            stores.add(store);
            maxDepth = store.getMaxDepth();
        }
        for (Path file : files) {
            if (file.toString().endsWith(".jfr")) {
                stores.addAll(Store.readJFR(file, maxDepth));
            }
        }
        System.out.println(Store.intervalsToTable(stores));
        // print table comparing all stores with each other
        System.out.println();
        List<Store> storesToCompare = stores.stream().filter(s -> !s.getName().contains("error")).toList();
        Map<Store, Map<Store, Float>> comp = storesToCompare.stream().collect(
                Collectors.toMap(Function.identity(), store -> storesToCompare.stream().collect(
                        Collectors.toMap(Function.identity(), store::differencePercentagePoints))));
        System.out.printf("%-20s", "");
        for (var s : storesToCompare) {
            System.out.printf("%-20s", s.getName());
        }
        System.out.println();
        System.out.println("-".repeat(20 * (storesToCompare.size() + 1)));
        for (var s : storesToCompare) {
            System.out.printf("%20s", s.getName());
            for (var s2 : storesToCompare) {
                System.out.printf("%20.2f", comp.get(s).get(s2));
            }
            System.out.println();
        }
    }

    public static void main(String[] args) {
        new CommandLine(new Main()).execute(args);
    }
}
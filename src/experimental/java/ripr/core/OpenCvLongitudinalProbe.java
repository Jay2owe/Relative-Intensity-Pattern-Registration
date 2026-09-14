/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.bytedeco.opencv.global.opencv_core.*;

/** Replays frozen feature and pair fixtures in Java, never launches Python or ImageJ. */
public final class OpenCvLongitudinalProbe {
    private static float[] read(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        float[] output = new float[bytes.length / 4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(output);
        return output;
    }

    private static void write(Path path, float[] values) throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asFloatBuffer().put(values);
        Files.write(path, bytes.array());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("fixture-directory output-directory");
        Path fixture = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectory(output);
        long loadStarted = System.nanoTime();
        setNumThreads(1);
        setUseOpenCL(false);
        Files.writeString(output.resolve("opencv_build.txt"), getBuildInformation().getString());
        Files.writeString(output.resolve("cpu_features.txt"), getCPUFeaturesLine().getString());
        double loadSeconds = (System.nanoTime() - loadStarted) / 1e9;
        OpenCvLongitudinalContractTest.run();
        Files.writeString(output.resolve("native_contract.txt"),
                "PASS: constant image; identity fit; repeated calls; input preservation; dimensions; filter; finite input; blank failure\n");
        String[] dimensions = Files.readString(fixture.resolve("dimensions.txt")).trim().split(",");
        int width = Integer.parseInt(dimensions[0]), height = Integer.parseInt(dimensions[1]);
        List<String> timings = new ArrayList<>();
        timings.add("operation,item,seconds");
        timings.add("native_load,first," + loadSeconds);
        for (String line : Files.readAllLines(fixture.resolve("features.txt"))) {
            if (line.isEmpty()) continue;
            float[] input = read(fixture.resolve(line + "_raw.f32"));
            long started = System.nanoTime();
            float[] feature = LongitudinalReferenceFeatures.emission(input, width, height,
                    OpenCvLongitudinalOps::gaussian);
            timings.add("emission," + line + "," + (System.nanoTime() - started) / 1e9);
            write(output.resolve(line + "_feature.f32"), feature);
        }
        List<String> results = new ArrayList<>();
        results.add("pair,status,score,a00,a01,a02,a10,a11,a12,seconds");
        List<String> phases = new ArrayList<>();
        phases.add("pair,a00,a01,a02,a10,a11,a12");
        for (String line : Files.readAllLines(fixture.resolve("pairs.txt"))) {
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            String id = parts[0];
            float[] reference = read(fixture.resolve(parts[1] + "_feature.f32"));
            float[] moving = read(fixture.resolve(parts[2] + "_feature.f32"));
            float[] seed = new float[6];
            for (int i = 0; i < 6; i++) seed[i] = Float.parseFloat(parts[i + 4]);
            if (id.endsWith("_phase")) {
                float[] phase = OpenCvLongitudinalOps.phase(reference, moving, width, height);
                StringBuilder row = new StringBuilder(id);
                for (float value : phase) row.append(',').append(value);
                phases.add(row.toString());
            }
            long started = System.nanoTime();
            try {
                OpenCvLongitudinalOps.Fit fit = OpenCvLongitudinalOps.fit(reference, moving,
                        width, height, seed, Integer.parseInt(parts[3]));
                StringBuilder row = new StringBuilder(id + ",ok," + fit.score);
                for (float value : fit.matrix) row.append(',').append(value);
                row.append(',').append((System.nanoTime() - started) / 1e9);
                results.add(row.toString());
            } catch (RuntimeException error) {
                // Preserve convergence failures instead of manufacturing a zero transform.
                results.add(id + ",failed,,,,,,,," + (System.nanoTime() - started) / 1e9);
                Files.writeString(output.resolve(id + "_failure.txt"), error.toString());
            }
        }
        Files.write(output.resolve("pairs.csv"), results);
        Files.write(output.resolve("phases.csv"), phases);
        Files.write(output.resolve("timings.csv"), timings);
    }
}

/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Loads real consecutive source frames without adding or altering their motion. */
final class NativeSeriesFrames {
    enum Layout { STACK, IMAGE_SEQUENCE }

    static final class Recording {
        final float[][] frames;
        final int width;
        final int sourceWidth;
        final int sourceHeight;
        final int firstFrame;
        final int stride;

        Recording(float[][] frames, int width, int sourceWidth, int sourceHeight,
                  int firstFrame, int stride) {
            this.frames = frames;
            this.width = width;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.firstFrame = firstFrame;
            this.stride = stride;
        }
    }

    private NativeSeriesFrames() { }

    static Recording load(Path source, Layout layout, int firstFrame, int stride,
                          int maxFrames, int cropSize) throws IOException {
        if (firstFrame < 1 || stride < 1 || maxFrames < 2 || cropSize < 64) {
            throw new IllegalArgumentException("invalid native-series selection");
        }
        List<Path> images = imageFiles(source);
        if (images.isEmpty()) throw new IOException("no source images under " + source);
        return layout == Layout.IMAGE_SEQUENCE
                ? loadSequence(images, firstFrame, stride, maxFrames, cropSize)
                : loadStack(images.get(0), firstFrame, stride, maxFrames, cropSize);
    }

    private static Recording loadSequence(List<Path> images, int firstFrame, int stride,
                                          int maxFrames, int cropSize) throws IOException {
        List<float[]> frames = new ArrayList<>();
        int sourceWidth = -1;
        int sourceHeight = -1;
        for (int index = firstFrame - 1; index < images.size() && frames.size() < maxFrames;
             index += stride) {
            ImagePlus image = IJ.openImage(images.get(index).toString());
            if (image == null) throw new IOException("could not open " + images.get(index));
            try {
                if (sourceWidth < 0) {
                    sourceWidth = image.getWidth();
                    sourceHeight = image.getHeight();
                }
                checkDimensions(image, sourceWidth, sourceHeight, images.get(index));
                frames.add(crop(image.getProcessor(), cropSize));
            } finally {
                image.close();
            }
        }
        return recording(frames, sourceWidth, sourceHeight, cropSize, firstFrame, stride);
    }

    private static Recording loadStack(Path path, int firstFrame, int stride,
                                       int maxFrames, int cropSize) throws IOException {
        ImagePlus image = IJ.openImage(path.toString());
        if (image == null) throw new IOException("could not open " + path);
        try {
            List<float[]> frames = new ArrayList<>();
            for (int index = firstFrame; index <= image.getStackSize() && frames.size() < maxFrames;
                 index += stride) {
                frames.add(crop(image.getStack().getProcessor(index), cropSize));
            }
            return recording(frames, image.getWidth(), image.getHeight(), cropSize,
                    firstFrame, stride);
        } finally {
            image.close();
        }
    }

    private static Recording recording(List<float[]> frames, int sourceWidth, int sourceHeight,
                                       int cropSize, int firstFrame, int stride)
            throws IOException {
        if (frames.size() < 2) throw new IOException("source contains fewer than two selected frames");
        int width = Math.min(cropSize, Math.min(sourceWidth, sourceHeight));
        return new Recording(frames.toArray(new float[0][]), width, sourceWidth, sourceHeight,
                firstFrame, stride);
    }

    private static float[] crop(ImageProcessor processor, int requested) {
        int width = Math.min(requested, Math.min(processor.getWidth(), processor.getHeight()));
        int x = (processor.getWidth() - width) / 2;
        int y = (processor.getHeight() - width) / 2;
        ImageProcessor copy = processor.duplicate();
        copy.setRoi(x, y, width, width);
        return (float[]) copy.crop().convertToFloatProcessor().getPixels();
    }

    private static void checkDimensions(ImagePlus image, int width, int height, Path path)
            throws IOException {
        if (image.getWidth() != width || image.getHeight() != height) {
            throw new IOException("frame dimensions changed at " + path);
        }
    }

    private static List<Path> imageFiles(Path source) throws IOException {
        List<Path> images = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile).filter(path -> {
                String normalized = path.toString().replace('\\', '/');
                if (normalized.contains("/annotations/")) return false;
                String name = path.getFileName().toString().toLowerCase();
                return name.endsWith(".tif") || name.endsWith(".tiff")
                        || name.endsWith(".stk") || name.endsWith(".png");
            }).forEach(images::add);
        }
        images.sort(Comparator.comparing(Path::toString));
        return images;
    }
}

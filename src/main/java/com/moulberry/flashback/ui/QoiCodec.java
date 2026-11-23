package com.moulberry.flashback.ui;

import com.mojang.blaze3d.platform.NativeImage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class QoiCodec {

    private static final byte[] MAGIC = new byte[]{'q', 'o', 'i', 'f'};
    private static final byte[] END_MARKER = new byte[]{0, 0, 0, 0, 0, 0, 0, 1};

    private QoiCodec() {
    }

    public static void write(Path path, int width, int height, PixelSource pixels) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(14 + width * height * 5 / 2)) {
            output.write(MAGIC);
            writeInt(output, width);
            writeInt(output, height);
            output.write(4);
            output.write(0);

            int[] index = new int[64];
            int previous = 0xFF000000;
            int run = 0;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = pixels.pixelAt(x, y);
                    if (pixel == previous) {
                        run += 1;
                        if (run == 62) {
                            output.write(0xC0 | (run - 1));
                            run = 0;
                        }
                        continue;
                    }

                    if (run > 0) {
                        output.write(0xC0 | (run - 1));
                        run = 0;
                    }

                    int indexPosition = colorHash(pixel);
                    if (index[indexPosition] == pixel) {
                        output.write(indexPosition);
                    } else {
                        index[indexPosition] = pixel;
                        int alpha = (pixel >>> 24) & 0xFF;
                        int previousAlpha = (previous >>> 24) & 0xFF;
                        if (alpha == previousAlpha) {
                            output.write(0xFE);
                            output.write((pixel >>> 16) & 0xFF);
                            output.write((pixel >>> 8) & 0xFF);
                            output.write(pixel & 0xFF);
                        } else {
                            output.write(0xFF);
                            output.write((pixel >>> 16) & 0xFF);
                            output.write((pixel >>> 8) & 0xFF);
                            output.write(pixel & 0xFF);
                            output.write(alpha);
                        }
                    }

                    previous = pixel;
                }
            }

            if (run > 0) {
                output.write(0xC0 | (run - 1));
            }
            output.write(END_MARKER);
            Files.write(path, output.toByteArray());
        }
    }

    public static NativeImage read(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        if (bytes.length < 22 || bytes[0] != 'q' || bytes[1] != 'o' || bytes[2] != 'i' || bytes[3] != 'f') {
            throw new IOException("Invalid QOI header");
        }

        int width = readInt(bytes, 4);
        int height = readInt(bytes, 8);
        int channels = bytes[12] & 0xFF;
        if (width <= 0 || height <= 0 || (channels != 3 && channels != 4)) {
            throw new IOException("Unsupported QOI image");
        }

        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        int[] index = new int[64];
        int pixel = 0xFF000000;
        int offset = 14;
        int run = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (run > 0) {
                    run -= 1;
                } else {
                    if (offset >= bytes.length) {
                        image.close();
                        throw new IOException("Unexpected end of QOI image");
                    }

                    int b1 = bytes[offset++] & 0xFF;
                    if (b1 == 0xFE) {
                        int r = bytes[offset++] & 0xFF;
                        int g = bytes[offset++] & 0xFF;
                        int b = bytes[offset++] & 0xFF;
                        pixel = (pixel & 0xFF000000) | (r << 16) | (g << 8) | b;
                    } else if (b1 == 0xFF) {
                        int r = bytes[offset++] & 0xFF;
                        int g = bytes[offset++] & 0xFF;
                        int b = bytes[offset++] & 0xFF;
                        int a = bytes[offset++] & 0xFF;
                        pixel = (a << 24) | (r << 16) | (g << 8) | b;
                    } else {
                        int tag = b1 & 0xC0;
                        if (tag == 0x00) {
                            pixel = index[b1];
                        } else if (tag == 0x40) {
                            int dr = ((b1 >>> 4) & 0x03) - 2;
                            int dg = ((b1 >>> 2) & 0x03) - 2;
                            int db = (b1 & 0x03) - 2;
                            pixel = addRgb(pixel, dr, dg, db);
                        } else if (tag == 0x80) {
                            int b2 = bytes[offset++] & 0xFF;
                            int dg = (b1 & 0x3F) - 32;
                            int dr = ((b2 >>> 4) & 0x0F) - 8 + dg;
                            int db = (b2 & 0x0F) - 8 + dg;
                            pixel = addRgb(pixel, dr, dg, db);
                        } else {
                            run = b1 & 0x3F;
                        }
                    }

                    index[colorHash(pixel)] = pixel;
                }

                image.setPixel(x, y, pixel);
            }
        }

        return image;
    }

    private static int addRgb(int pixel, int dr, int dg, int db) {
        int r = (((pixel >>> 16) & 0xFF) + dr) & 0xFF;
        int g = (((pixel >>> 8) & 0xFF) + dg) & 0xFF;
        int b = ((pixel & 0xFF) + db) & 0xFF;
        return (pixel & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int colorHash(int pixel) {
        int a = (pixel >>> 24) & 0xFF;
        int r = (pixel >>> 16) & 0xFF;
        int g = (pixel >>> 8) & 0xFF;
        int b = pixel & 0xFF;
        return (r * 3 + g * 5 + b * 7 + a * 11) & 63;
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) |
            ((bytes[offset + 1] & 0xFF) << 16) |
            ((bytes[offset + 2] & 0xFF) << 8) |
            (bytes[offset + 3] & 0xFF);
    }

    @FunctionalInterface
    public interface PixelSource {
        /**
         * Returns a Java-side ARGB pixel. QOI bytes are written/read as RGBA.
         */
        int pixelAt(int x, int y);
    }
}

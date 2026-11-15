package com.bizbitenow.printservice.job.service;

import com.bizbitenow.printservice.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;

@Component
public class EscPosReceiptGenerator {
    private static final byte ESC = 0x1b;
    private static final byte GS = 0x1d;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    public byte[] generate(JsonNode payload) {
        return generate(payload, true);
    }

    public byte[] generate(JsonNode payload, boolean cutPaper) {
        if ("ESC_POS_BASE64".equals(payload.path("format").asText())) {
            try {
                byte[] bytes = Base64.getDecoder().decode(payload.path("data").asText());
                if (bytes.length == 0 || bytes.length > MAX_PAYLOAD_BYTES) invalid("Invalid ESC/POS payload size");
                return bytes;
            } catch (IllegalArgumentException ex) {
                throw invalidPayload("ESC/POS payload must be valid base64");
            }
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(new byte[]{ESC, '@'});
        output.writeBytes(new byte[]{ESC, 't', (byte) 255});
        for (JsonNode block : payload.path("blocks")) {
            switch (block.path("type").asText()) {
                case "TEXT" -> textBlock(output, block);
                case "TWO_COLUMN" -> {
                    bold(output, block.path("bold").asBoolean());
                    align(output, 0);
                    line(output, columns(block.path("left").asText(), block.path("right").asText(),
                            columns(payload)));
                    bold(output, false);
                }
                case "ITEM_ROW" -> {
                    int width = columns(payload);
                    String item = block.path("name").asText() + " x" + block.path("qty").asText();
                    line(output, columns(item, block.path("total").asText(), width));
                    line(output, "  " + block.path("price").asText());
                }
                case "DIVIDER" -> {
                    int width = columns(payload);
                    String character = safeText(block.path("char").asText());
                    String unit = character.isEmpty() ? "-" : truncate(character, width);
                    int count = Math.max(1, width / Math.max(1, unit.codePointCount(0, unit.length())));
                    line(output, unit.repeat(count));
                }
                case "FEED" -> output.writeBytes(new byte[]{ESC, 'd', (byte) block.path("lines").asInt()});
                case "QR" -> qr(output, block);
                case "BARCODE" -> barcode(output, block);
                case "IMAGE" -> image(output, block, columns(payload) == 32 ? 384 : 576);
                default -> throw invalidPayload("Unsupported receipt block type");
            }
        }
        output.writeBytes(new byte[]{ESC, 'a', 0});
        output.writeBytes(new byte[]{ESC, 'd', 3});
        if (cutPaper) output.writeBytes(new byte[]{GS, 'V', 0});
        byte[] bytes = output.toByteArray();
        if (bytes.length > MAX_PAYLOAD_BYTES) invalid("Generated ESC/POS payload exceeds 256 KB");
        return bytes;
    }

    public byte[] testPayload(int paperWidthMm) {
        if (paperWidthMm != 58 && paperWidthMm != 80) invalid("paperWidthMm must be 58 or 80");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(new byte[]{ESC, '@', ESC, 't', (byte) 255, ESC, 'a', 1, ESC, 'E', 1});
        write(output, "PRINTER TEST\n");
        output.writeBytes(new byte[]{ESC, 'E', 0});
        write(output, paperWidthMm + "mm paper\n");
        write(output, "Rupee support: ₹\n");
        write(output, "ESC/POS device confirmation required\n");
        output.writeBytes(new byte[]{ESC, 'a', 0, ESC, 'd', 3, GS, 'V', 0});
        return output.toByteArray();
    }

    private void textBlock(ByteArrayOutputStream output, JsonNode block) {
        align(output, switch (block.path("align").asText()) {
            case "CENTER" -> 1;
            case "RIGHT" -> 2;
            default -> 0;
        });
        bold(output, block.path("bold").asBoolean());
        int size = switch (block.path("size").asText("NORMAL")) {
            case "DOUBLE_HEIGHT" -> 1;
            case "DOUBLE_WIDTH" -> 16;
            case "LARGE" -> 17;
            default -> 0;
        };
        output.writeBytes(new byte[]{GS, '!', (byte) size});
        line(output, block.path("text").asText());
        output.writeBytes(new byte[]{GS, '!', 0});
        bold(output, false);
        align(output, 0);
    }

    private void qr(ByteArrayOutputStream output, JsonNode block) {
        byte[] data = safeText(block.path("data").asText()).getBytes(StandardCharsets.UTF_8);
        int length = data.length + 3;
        align(output, switch (block.path("align").asText()) {
            case "CENTER" -> 1;
            case "RIGHT" -> 2;
            default -> 0;
        });
        output.writeBytes(new byte[]{GS, '(', 'k', 4, 0, 49, 65, 50, 0});
        output.writeBytes(new byte[]{GS, '(', 'k', 3, 0, 49, 67, (byte) block.path("size").asInt()});
        output.writeBytes(new byte[]{GS, '(', 'k', 3, 0, 49, 69, 48});
        output.writeBytes(new byte[]{GS, '(', 'k', (byte) length, (byte) (length >> 8), 49, 80, 48});
        output.writeBytes(data);
        output.writeBytes(new byte[]{GS, '(', 'k', 3, 0, 49, 81, 48});
        line(output, "");
        align(output, 0);
    }

    private void barcode(ByteArrayOutputStream output, JsonNode block) {
        String format = block.path("format").asText().toUpperCase();
        int mode = switch (format) {
            case "UPCA" -> 0;
            case "EAN13" -> 2;
            case "CODE39" -> 4;
            case "CODE128" -> 73;
            default -> throw invalidPayload("Unsupported barcode format");
        };
        String data = safeText(block.path("data").asText());
        if (format.equals("CODE128")) data = "{B" + data;
        align(output, switch (block.path("align").asText()) {
            case "CENTER" -> 1;
            case "RIGHT" -> 2;
            default -> 0;
        });
        output.writeBytes(new byte[]{GS, 'H', 2, GS, 'k', (byte) mode});
        write(output, data);
        output.write(0);
        line(output, "");
        align(output, 0);
    }

    private void image(ByteArrayOutputStream output, JsonNode block, int paperDots) {
        byte[] source;
        try {
            source = Base64.getDecoder().decode(block.path("base64").asText());
        } catch (IllegalArgumentException ex) {
            throw invalidPayload("Receipt image must be valid base64");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) throw invalidPayload("Unsupported receipt image format");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidPayload("Unsupported receipt image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int originalWidth = reader.getWidth(0);
                int originalHeight = reader.getHeight(0);
                if (originalWidth < 1 || originalHeight < 1 || originalWidth > 4096 || originalHeight > 4096
                        || (long) originalWidth * originalHeight > 8_000_000L) {
                    throw invalidPayload("Receipt image dimensions are too large");
                }
                BufferedImage sourceImage = reader.read(0);
                double scale = Math.min(1.0, Math.min(
                        Math.min(block.path("maxWidthPx").asInt(), paperDots) / (double) originalWidth,
                        2048.0 / originalHeight));
                int width = Math.max(1, (int) Math.round(originalWidth * scale));
                int height = Math.max(1, (int) Math.round(originalHeight * scale));
                int widthBytes = (width + 7) / 8;
                byte[] raster = new byte[widthBytes * height];
                for (int y = 0; y < height; y++) {
                    int sourceY = Math.min(originalHeight - 1, (int) (y / scale));
                    for (int x = 0; x < width; x++) {
                        int sourceX = Math.min(originalWidth - 1, (int) (x / scale));
                        int color = sourceImage.getRGB(sourceX, sourceY);
                        int red = (color >> 16) & 0xff;
                        int green = (color >> 8) & 0xff;
                        int blue = color & 0xff;
                        if ((red * 299 + green * 587 + blue * 114) / 1000 < 160) {
                            raster[y * widthBytes + x / 8] |= (byte) (0x80 >> (x % 8));
                        }
                    }
                }
                align(output, switch (block.path("align").asText()) {
                    case "CENTER" -> 1;
                    case "RIGHT" -> 2;
                    default -> 0;
                });
                output.writeBytes(new byte[]{GS, 'v', '0', 0,
                        (byte) widthBytes, (byte) (widthBytes >> 8),
                        (byte) height, (byte) (height >> 8)});
                output.writeBytes(raster);
                line(output, "");
                align(output, 0);
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            throw invalidPayload("Unable to decode receipt image");
        }
    }

    private static int columns(JsonNode payload) {
        return payload.path("paperWidthMm").asInt() == 58 ? 32 : 48;
    }

    private static String columns(String left, String right, int width) {
        left = safeText(left);
        right = truncate(safeText(right), width);
        int leftWidth = Math.max(0, width - right.codePointCount(0, right.length()));
        left = truncate(left, leftWidth);
        int spaces = Math.max(1, width - left.codePointCount(0, left.length())
                - right.codePointCount(0, right.length()));
        return left + " ".repeat(spaces) + right;
    }

    private static String truncate(String value, int maxCodePoints) {
        int length = value.codePointCount(0, value.length());
        return length <= maxCodePoints ? value : value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static String safeText(String value) {
        return value.codePoints().filter(codePoint -> codePoint >= 32 || codePoint == '\n' || codePoint == '\r')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    private static void line(ByteArrayOutputStream output, String text) {
        write(output, safeText(text));
        output.write('\n');
    }

    private static void write(ByteArrayOutputStream output, String text) {
        output.writeBytes(safeText(text).getBytes(StandardCharsets.UTF_8));
    }

    private static void align(ByteArrayOutputStream output, int value) {
        output.writeBytes(new byte[]{ESC, 'a', (byte) value});
    }

    private static void bold(ByteArrayOutputStream output, boolean value) {
        output.writeBytes(new byte[]{ESC, 'E', (byte) (value ? 1 : 0)});
    }

    private static void invalid(String message) {
        throw invalidPayload(message);
    }

    private static BusinessException invalidPayload(String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAYLOAD", message);
    }
}

package com.bizbitenow.printservice.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class ReceiptPayloadValidator {
    private static final int MAX_BYTES = 256 * 1024;
    private static final Set<String> ALIGNMENTS = Set.of("LEFT", "CENTER", "RIGHT");
    private static final Set<String> SIZES = Set.of("NORMAL", "DOUBLE_HEIGHT", "DOUBLE_WIDTH", "LARGE");
    private final ObjectMapper objectMapper;

    public ReceiptPayloadValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(JsonNode payload) {
        if (payload == null || !payload.isObject()) invalid("Payload must be a JSON object");
        if (objectMapper.valueToTree(payload).toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            invalid("Payload exceeds 256 KB");
        }
        JsonNode width = payload.get("paperWidthMm");
        if (width == null || !width.canConvertToInt() || (width.intValue() != 58 && width.intValue() != 80)) {
            invalid("paperWidthMm must be 58 or 80");
        }
        JsonNode blocks = payload.get("blocks");
        if (blocks == null || !blocks.isArray() || blocks.isEmpty() || blocks.size() > 500) {
            invalid("blocks must contain between 1 and 500 entries");
        }
        for (JsonNode block : blocks) validateBlock(block);
    }

    private void validateBlock(JsonNode block) {
        if (!block.isObject() || !text(block, "type")) invalid("Each block must have a type");
        switch (block.get("type").asText()) {
            case "TEXT" -> {
                requireText(block, "text");
                requireEnum(block, "align", ALIGNMENTS);
                requireEnum(block, "size", SIZES);
                requireBoolean(block, "bold");
            }
            case "TWO_COLUMN" -> {
                requireText(block, "left");
                requireText(block, "right");
                requireBoolean(block, "bold");
            }
            case "ITEM_ROW" -> {
                requireText(block, "name");
                JsonNode qty = block.get("qty");
                if (qty == null || !qty.canConvertToInt() || qty.intValue() < 1 || qty.intValue() > 10000) {
                    invalid("ITEM_ROW qty must be between 1 and 10000");
                }
                requireText(block, "price");
                requireText(block, "total");
            }
            case "DIVIDER" -> requireText(block, "char");
            case "IMAGE" -> {
                requireText(block, "base64");
                requireEnum(block, "align", ALIGNMENTS);
                JsonNode maxWidth = block.get("maxWidthPx");
                if (maxWidth == null || !maxWidth.canConvertToInt() || maxWidth.intValue() < 1 || maxWidth.intValue() > 1024) {
                    invalid("IMAGE maxWidthPx must be between 1 and 1024");
                }
            }
            case "QR" -> {
                requireText(block, "data");
                requireEnum(block, "align", ALIGNMENTS);
                JsonNode size = block.get("size");
                if (size == null || !size.canConvertToInt() || size.intValue() < 1 || size.intValue() > 16) {
                    invalid("QR size must be between 1 and 16");
                }
            }
            case "BARCODE" -> {
                requireText(block, "data");
                requireText(block, "format");
                requireEnum(block, "align", ALIGNMENTS);
            }
            case "FEED" -> {
                JsonNode lines = block.get("lines");
                if (lines == null || !lines.canConvertToInt() || lines.intValue() < 0 || lines.intValue() > 20) {
                    invalid("FEED lines must be between 0 and 20");
                }
            }
            default -> invalid("Unsupported receipt block type");
        }
    }

    private boolean text(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isTextual();
    }

    private void requireText(JsonNode node, String field) {
        if (!text(node, field) || node.get(field).asText().length() > 10000) invalid("Invalid " + field);
    }

    private void requireBoolean(JsonNode node, String field) {
        if (!node.has(field) || !node.get(field).isBoolean()) invalid("Invalid " + field);
    }

    private void requireEnum(JsonNode node, String field, Set<String> values) {
        if (!text(node, field) || !values.contains(node.get(field).asText())) invalid("Invalid " + field);
    }

    private void invalid(String message) {
        throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAYLOAD", message);
    }
}

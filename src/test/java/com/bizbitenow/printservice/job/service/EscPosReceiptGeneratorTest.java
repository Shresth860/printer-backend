package com.bizbitenow.printservice.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class EscPosReceiptGeneratorTest {
    private final EscPosReceiptGenerator generator = new EscPosReceiptGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generatesEscPosCommandsAndUtf8RupeeForReceipt() throws Exception {
        var payload = objectMapper.readTree("""
                {"paperWidthMm":58,"blocks":[
                  {"type":"TEXT","text":"Receipt","align":"CENTER","bold":true,"size":"LARGE"},
                  {"type":"ITEM_ROW","name":"Tea","qty":2,"price":"₹10","total":"₹20"},
                  {"type":"TEXT","text":"Total ₹20","align":"RIGHT","bold":true,"size":"NORMAL"}
                ]}
                """);

        byte[] bytes = generator.generate(payload);
        String text = new String(bytes, StandardCharsets.UTF_8);

        assertArrayEquals(new byte[]{0x1b, '@'}, Arrays.copyOf(bytes, 2));
        assertTrue(text.contains("₹10"));
        assertTrue(text.contains("₹20"));
        assertTrue(contains(bytes, new byte[]{0x1d, '!', 17}));
        assertTrue(contains(bytes, new byte[]{0x1d, 'V', 0}));
    }

    @Test
    void testPayloadIsPrinterWidthSpecificAndContainsInitializationAndCut() {
        byte[] testPayload = generator.testPayload(80);

        assertTrue(new String(testPayload, StandardCharsets.UTF_8).contains("80mm paper"));
        assertTrue(new String(testPayload, StandardCharsets.UTF_8).contains("Rupee support: ₹"));
        assertTrue(contains(testPayload, new byte[]{0x1b, '@'}));
        assertTrue(contains(testPayload, new byte[]{0x1d, 'V', 0}));
        assertThrows(RuntimeException.class, () -> generator.testPayload(72));
    }

    private static boolean contains(byte[] source, byte[] target) {
        outer:
        for (int i = 0; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}

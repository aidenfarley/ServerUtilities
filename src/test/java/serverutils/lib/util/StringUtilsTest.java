package serverutils.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StringUtilsTest {

    private Locale previousLocale;

    @BeforeEach
    void rememberLocale() {
        previousLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(previousLocale);
    }

    @Test
    void identifiersAreNormalizedUsingExistingRules() {
        assertEquals("mixed_case_id", StringUtils.getID("Mixed Case-ID", StringUtils.FLAG_ID_DEFAULTS));
        assertEquals(
                "mixed_case",
                StringUtils.getID("Mixed Case", StringUtils.FLAG_ID_FIX | StringUtils.FLAG_ID_ONLY_UNDERLINE));
        assertThrows(
                IllegalArgumentException.class,
                () -> StringUtils.getID("Uppercase", StringUtils.FLAG_ID_ONLY_UNDERLINE));
    }

    @Test
    void compactUuidRoundTrips() {
        UUID expected = UUID.fromString("12345678-1234-5678-9abc-def012345678");

        assertEquals(expected, StringUtils.fromString(StringUtils.fromUUID(expected)));
        assertNull(StringUtils.fromString("not-a-uuid"));
    }

    @Test
    void identifiersAndNumbersDoNotDependOnDefaultLocale() {
        Locale.setDefault(new Locale("tr", "TR"));

        assertEquals("identifier", StringUtils.getID("IDENTIFIER", StringUtils.FLAG_ID_DEFAULTS));
        assertEquals("1.2", StringUtils.formatDouble0(1.29D));
        assertEquals("1.29", StringUtils.formatDouble00(1.299D));
        assertEquals("1", StringUtils.formatDouble00(1D));
    }

    @Test
    void numberFormattingIsSafeAcrossThreads() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                tasks.add(() -> StringUtils.formatDouble00(1234.567D));
            }

            for (Future<String> result : executor.invokeAll(tasks)) {
                assertEquals("1234.56", result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deprecatedFormattingStateRemainsBehaviorallyCompatible() {
        String previousPattern;
        synchronized (StringUtils.DOUBLE_FORMATTER_00) {
            previousPattern = StringUtils.DOUBLE_FORMATTER_00.toPattern();
            StringUtils.DOUBLE_FORMATTER_00.applyPattern("0.000");
        }

        int previousFirstThreshold = StringUtils.INT_SIZE_TABLE[0];
        try {
            assertEquals("1.234", StringUtils.formatDouble00(1.2349D));
            StringUtils.INT_SIZE_TABLE[0] = 0;
            assertEquals(2, StringUtils.stringSize(1));
        } finally {
            synchronized (StringUtils.DOUBLE_FORMATTER_00) {
                StringUtils.DOUBLE_FORMATTER_00.applyPattern(previousPattern);
            }
            StringUtils.INT_SIZE_TABLE[0] = previousFirstThreshold;
        }
    }

    @Test
    void motdStyleFormattingPreservesHalfEvenRounding() {
        assertEquals("20.0", NumberFormatUtils.formatRoundedOneDecimal(19.96D));
    }
}

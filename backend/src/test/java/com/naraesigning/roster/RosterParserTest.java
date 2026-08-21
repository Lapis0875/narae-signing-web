package com.naraesigning.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class RosterParserTest {
    private final RosterCsvParser csv = new RosterCsvParser();
    private final RosterXlsxParser xlsx = new RosterXlsxParser();
    private final RosterJsonParser json = new RosterJsonParser(new ObjectMapper());

    @BeforeAll
    static void zipSafety() { RosterXlsxParser.configureZipSafety(); }

    @Test
    void equivalentExactTriples_whenTwentyFiveRowsUseCsvXlsxOrJson() throws Exception {
        var expected = IntStream.range(0, 25)
                .mapToObj(index -> new RosterIdentity(index % 2 == 0 ? "" : " 소속 " + index,
                        index % 3 == 0 ? "" : "직책\t" + index, "이름 " + index))
                .toList();

        var fromCsv = csv.parse(csv(expected));
        var fromXlsx = xlsx.parse(xlsx(expected, false));
        var fromJson = json.parse(new ObjectMapper().writeValueAsBytes(java.util.Map.of("rows", expected)));

        assertThat(fromCsv).containsExactlyElementsOf(expected);
        assertThat(fromXlsx).containsExactlyElementsOf(expected);
        assertThat(fromJson).containsExactlyElementsOf(expected);
        System.out.println("QA roster_25_equivalent=true exact_utf8=true");
    }

    @Test
    void stableSafeErrors_whenCsvIsMalformed() {
        assertCode(() -> csv.parse("\ufeff소속사,직책,이름\nA,B,C".getBytes(StandardCharsets.UTF_8)), "INVALID_ENCODING");
        assertCode(() -> csv.parse("소속사;직책;이름\nA;B;C".getBytes(StandardCharsets.UTF_8)), "INVALID_HEADER");
        assertCode(() -> csv.parse("소속사,직책,이름,추가\nA,B,C,D".getBytes(StandardCharsets.UTF_8)), "INVALID_HEADER");
        assertCode(() -> csv.parse("소속사,직책,이름\nA,B".getBytes(StandardCharsets.UTF_8)), "INVALID_FIELDS");
        assertCode(() -> csv.parse("소속사,직책,이름\nA,B, \t".getBytes(StandardCharsets.UTF_8)), "BLANK_NAME");
        var rows = IntStream.rangeClosed(1, 51).mapToObj(i -> "A,B,N" + i).toList();
        assertCode(() -> csv.parse(("소속사,직책,이름\n" + String.join("\n", rows))
                .getBytes(StandardCharsets.UTF_8)), "ROW_LIMIT");
    }

    @Test
    void rejectsSecondSheetAndZipBomb_withoutLeakingParserDetails() throws Exception {
        assertCode(() -> xlsx.parse(xlsx(java.util.List.of(new RosterIdentity("A", "B", "C")), true)),
                "INVALID_SHEET_COUNT");
        assertCode(() -> xlsx.parse(zipBomb()), "INVALID_XLSX");
    }

    @Test
    void rejectsUnknownOrMissingJsonFields() {
        assertCode(() -> json.parse("{\"rows\":[{\"organization\":\"A\",\"job\":\"B\",\"name\":\"C\",\"extra\":1}]}"
                .getBytes(StandardCharsets.UTF_8)), "INVALID_FIELDS");
        assertCode(() -> json.parse("{\"rows\":[{\"organization\":\"A\",\"name\":\"C\"}]}"
                .getBytes(StandardCharsets.UTF_8)), "INVALID_FIELDS");
    }

    @Test
    void parserEntryCountersStayZero_whenPartOrRawBodyExceedsLimit() {
        csv.resetEntryCount();
        json.resetEntryCount();
        var oversized = new byte[RosterCsvParser.MAX_BYTES + 1];

        assertCode(() -> csv.parse(oversized), "FILE_TOO_LARGE");
        assertCode(() -> json.parse(oversized), "FILE_TOO_LARGE");

        assertThat(csv.entryCount()).isZero();
        assertThat(json.entryCount()).isZero();
    }

    @Test
    void entersParserAtInclusiveOneMebibyteCsvPartLimit_thenAppliesFieldBound() {
        var prefix = "소속사,직책,이름\n,,".getBytes(StandardCharsets.UTF_8);
        var bytes = new byte[RosterCsvParser.MAX_BYTES];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        java.util.Arrays.fill(bytes, prefix.length, bytes.length, (byte) 'N');

        assertCode(() -> csv.parse(bytes), "FIELD_TOO_LONG");
        assertThat(csv.entryCount()).isOne();
    }

    private static byte[] csv(java.util.List<RosterIdentity> rows) {
        var text = new StringBuilder("소속사,직책,이름\n");
        rows.forEach(row -> text.append(row.organization()).append(',').append(row.job()).append(',')
                .append(row.name()).append('\n'));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] xlsx(java.util.List<RosterIdentity> rows, boolean secondSheet) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("명단");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("소속사");
            header.createCell(1).setCellValue("직책");
            header.createCell(2).setCellValue("이름");
            for (int index = 0; index < rows.size(); index++) {
                var row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(rows.get(index).organization());
                row.createCell(1).setCellValue(rows.get(index).job());
                row.createCell(2).setCellValue(rows.get(index).name());
            }
            if (secondSheet) workbook.createSheet("추가");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] zipBomb() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("명단");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("소속사");
            header.createCell(1).setCellValue("직책");
            header.createCell(2).setCellValue("이름");
            for (int index = 0; index < 40; index++) {
                var row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(index + "-" + "A".repeat(32_000));
                row.createCell(1).setCellValue("");
                row.createCell(2).setCellValue("C" + index);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(RosterInputException.class).hasMessage(code);
    }
}

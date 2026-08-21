package com.naraesigning.roster;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class RosterXlsxParser {
    private static final String[] HEADER = {"소속사", "직책", "이름"};
    private final AtomicInteger entries = new AtomicInteger();

    List<RosterIdentity> parse(byte[] bytes) {
        if (bytes.length > RosterCsvParser.MAX_BYTES) throw new RosterInputException("FILE_TOO_LARGE");
        entries.incrementAndGet();
        try (var input = new ByteArrayInputStream(bytes);
                var container = OPCPackage.open(input);
                var workbook = new XSSFWorkbook(container)) {
            if (workbook.getNumberOfSheets() != 1) throw new RosterInputException("INVALID_SHEET_COUNT");
            var sheet = workbook.getSheetAt(0);
            requireHeader(sheet.getRow(0));
            var rawRows = new ArrayList<RawRosterRow>();
            var errors = new ArrayList<RosterValidationError>();
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                var row = sheet.getRow(rowIndex);
                if (row == null || row.getLastCellNum() > 3) {
                    errors.add(new RosterValidationError(rowIndex, "INVALID_FIELDS"));
                } else {
                    try {
                        rawRows.add(new RawRosterRow(rowIndex, value(row, 0), value(row, 1), value(row, 2)));
                    } catch (RosterInputException exception) {
                        errors.add(new RosterValidationError(rowIndex, exception.errors().getFirst().code()));
                    }
                }
            }
            return RosterRowValidator.validate(rawRows, errors);
        } catch (RosterInputException exception) {
            throw exception;
        } catch (IOException | OpenXML4JException | RuntimeException exception) {
            throw new RosterInputException("INVALID_XLSX");
        }
    }

    int entryCount() { return entries.get(); }
    void resetEntryCount() { entries.set(0); }

    private static void requireHeader(Row row) {
        if (row == null || row.getLastCellNum() != 3) throw new RosterInputException("INVALID_HEADER");
        for (int index = 0; index < HEADER.length; index++) {
            if (!HEADER[index].equals(value(row, index))) throw new RosterInputException("INVALID_HEADER");
        }
    }

    private static String value(Row row, int index) {
        var cell = row.getCell(index, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        if (cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() != CellType.STRING) throw new RosterInputException("INVALID_FIELDS");
        return cell.getStringCellValue();
    }

    static void configureZipSafety() {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(RosterCsvParser.MAX_BYTES);
        ZipSecureFile.setMaxTextSize(RosterCsvParser.MAX_BYTES);
    }
}

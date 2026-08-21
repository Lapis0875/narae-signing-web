package com.naraesigning.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.hasSize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.crypto.VersionedCryptoService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

final class RosterHttpApiTest {
    private static final UUID BOARD_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private InMemoryRosterRepository repository;

    @BeforeEach
    void setUp() {
        var key = new byte[32];
        Arrays.fill(key, (byte) 0x33);
        repository = new InMemoryRosterRepository();
        var service = new RosterService(repository,
                new VersionedCryptoService(Map.of(1, key), 1), directTransactions());
        var controller = new RosterController(service, new RosterJsonParser(mapper),
                new RosterCsvParser(), new RosterXlsxParser());
        RosterXlsxParser.configureZipSafety();
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new RosterApiAdvice()).build();
    }

    @Test
    void putCsvAndXlsxYieldEquivalentExactTriplesAndRetainedUuids() throws Exception {
        var rows = IntStream.range(0, 25)
                .mapToObj(index -> new RosterIdentity(index % 2 == 0 ? "" : " 소속 " + index,
                        index % 3 == 0 ? "" : "직책\t" + index, "이름 " + index))
                .toList();

        var jsonResult = mvc.perform(put(path()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("rows", rows))))
                .andExpect(status().isOk()).andReturn();
        var jsonEntries = mapper.readTree(jsonResult.getResponse().getContentAsByteArray());

        var csvEntries = importFile("roster.csv", "text/csv", csv(rows));
        var xlsxEntries = importFile("roster.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(rows));

        assertThat(triples(csvEntries)).isEqualTo(triples(jsonEntries));
        assertThat(triples(xlsxEntries)).isEqualTo(triples(jsonEntries));
        assertThat(ids(csvEntries)).isEqualTo(ids(jsonEntries));
        assertThat(ids(xlsxEntries)).isEqualTo(ids(jsonEntries));
        assertThat(slots(xlsxEntries)).containsOnly("UNPLACED");
        System.out.println("QA http_put_csv_xlsx_25=true retained_uuid_slot=true");
    }

    @Test
    void returnsBoundedOrderedSafeErrors_withoutEchoingRawRows() throws Exception {
        var rawSecret = "do-not-echo-this-name";
        var rows = new java.util.ArrayList<String>();
        for (int index = 0; index < 25; index++) {
            rows.add("{\"organization\":\"" + rawSecret + index
                    + "\",\"job\":\"B\",\"name\":\" \"}");
        }
        var body = "{\"rows\":[" + String.join(",", rows) + "]}";

        var response = mvc.perform(put(path()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROSTER_INVALID"))
                .andExpect(jsonPath("$.errors", hasSize(20)))
                .andExpect(jsonPath("$.errors[0].row").value(1))
                .andExpect(jsonPath("$.errors[19].row").value(20))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(rawSecret, "organization", "job", "name");
        System.out.println("QA ordered_safe_errors=20 raw_row_echo=false");
    }

    @Test
    void rejectsDuplicateOrExtraMultipartPartsAndUnexpectedParameters() throws Exception {
        var valid = "소속사,직책,이름\nA,B,C".getBytes(StandardCharsets.UTF_8);
        var first = new MockMultipartFile("file", "one.csv", "text/csv", valid);
        var second = new MockMultipartFile("file", "two.csv", "text/csv", valid);
        mvc.perform(multipart(path() + "/import").file(first).file(second))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(path() + "/import").file(first)
                        .file(new MockMultipartFile("extra", "extra.csv", "text/csv", valid)))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(path() + "/import").file(first).param("mode", "unsafe"))
                .andExpect(status().isBadRequest());
        System.out.println("QA duplicate_extra_parts=rejected unexpected_params=rejected");
    }

    @Test
    void openDirectCrudAllowsUnsubmittedAndRejectsSubmittedUntilReset() throws Exception {
        var firstPost = mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("A", "B", "C")))
                .andExpect(status().isOk()).andReturn();
        var firstId = mapper.readTree(firstPost.getResponse().getContentAsByteArray()).get("id").asText();
        repository.open();

        mvc.perform(patch(path() + "/" + firstId).contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson(" A ", "B2", "C2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.organization").value(" A "));
        mvc.perform(delete(path() + "/" + firstId))
                .andExpect(status().isNoContent());

        var submittedPost = mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("D", "E", "F")))
                .andExpect(status().isOk()).andReturn();
        var submittedId = mapper.readTree(submittedPost.getResponse().getContentAsByteArray()).get("id").asText();
        repository.markSubmitted(UUID.fromString(submittedId));

        mvc.perform(patch(path() + "/" + submittedId).contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("X", "Y", "Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROSTER_UNAVAILABLE"));
        mvc.perform(delete(path() + "/" + submittedId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROSTER_UNAVAILABLE"));

        repository.resetSubmitted(UUID.fromString(submittedId));
        mvc.perform(patch(path() + "/" + submittedId).contentType(MediaType.APPLICATION_JSON)
                        .content(identityJson("X", "Y", "Z")))
                .andExpect(status().isOk());
        mvc.perform(delete(path() + "/" + submittedId))
                .andExpect(status().isNoContent());
        System.out.println("QA http_open_post_patch_delete_unsubmitted=success submitted=409 "
                + "safe_code=ROSTER_UNAVAILABLE reset_then_mutate=success");
    }

    private JsonNode importFile(String name, String type, byte[] body) throws Exception {
        var file = new MockMultipartFile("file", name, type, body);
        var result = mvc.perform(multipart(path() + "/import").file(file))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<String> triples(JsonNode entries) {
        return java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                .map(node -> node.get("identity").toString()).toList();
    }

    private static List<String> ids(JsonNode entries) {
        return java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                .map(node -> node.get("id").asText()).toList();
    }

    private static List<String> slots(JsonNode entries) {
        return java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                .map(node -> node.get("slot").get("placementStatus").asText()).toList();
    }

    private static byte[] csv(List<RosterIdentity> rows) {
        var text = new StringBuilder("소속사,직책,이름\n");
        rows.forEach(row -> text.append(row.organization()).append(',').append(row.job()).append(',')
                .append(row.name()).append('\n'));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] xlsx(List<RosterIdentity> rows) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("명단");
            var header = sheet.createRow(0);
            for (int index = 0; index < 3; index++) {
                header.createCell(index).setCellValue(List.of("소속사", "직책", "이름").get(index));
            }
            for (int index = 0; index < rows.size(); index++) {
                var row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(rows.get(index).organization());
                row.createCell(1).setCellValue(rows.get(index).job());
                row.createCell(2).setCellValue(rows.get(index).name());
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static String path() { return "/api/v1/admin/boards/" + BOARD_ID + "/roster"; }

    private byte[] identityJson(String organization, String job, String name) throws Exception {
        return mapper.writeValueAsBytes(Map.of("organization", organization, "job", job, "name", name));
    }

    private static TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }
}

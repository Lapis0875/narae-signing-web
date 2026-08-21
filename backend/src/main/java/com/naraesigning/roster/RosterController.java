package com.naraesigning.roster;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty("spring.datasource.url")
@RequestMapping("/api/v1/admin/boards/{boardId}/roster")
final class RosterController {
    private final RosterService service;
    private final RosterJsonParser json;
    private final RosterCsvParser csv;
    private final RosterXlsxParser xlsx;

    RosterController(RosterService service, RosterJsonParser json, RosterCsvParser csv, RosterXlsxParser xlsx) {
        this.service = service;
        this.json = json;
        this.csv = csv;
        this.xlsx = xlsx;
    }

    @GetMapping
    List<RosterEntry> list(@PathVariable UUID boardId) {
        return service.list(boardId);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    RosterEntry create(@PathVariable UUID boardId, @RequestBody byte[] body) {
        return service.create(boardId, json.parseIdentity(body));
    }

    @PatchMapping(path = "/{entryId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    RosterEntry update(
            @PathVariable UUID boardId,
            @PathVariable UUID entryId,
            @RequestBody byte[] body) {
        return service.update(boardId, entryId, json.parseIdentity(body));
    }

    @DeleteMapping("/{entryId}")
    ResponseEntity<Void> delete(@PathVariable UUID boardId, @PathVariable UUID entryId) {
        service.delete(boardId, entryId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    List<RosterEntry> replace(@PathVariable UUID boardId, @RequestBody byte[] body) {
        return service.replace(boardId, json.parse(body));
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    List<RosterEntry> importFile(@PathVariable UUID boardId, MultipartHttpServletRequest request) {
        var files = request.getMultiFileMap();
        if (!request.getParameterMap().isEmpty() || files.size() != 1
                || !files.containsKey("file") || files.get("file").size() != 1) {
            throw new RosterInputException("INVALID_MULTIPART");
        }
        var file = files.get("file").getFirst();
        if (file == null || file.getOriginalFilename() == null) {
            throw new RosterInputException("INVALID_MULTIPART");
        }
        if (file.getSize() > RosterCsvParser.MAX_BYTES) throw new RosterInputException("FILE_TOO_LARGE");
        var bytes = read(file);
        var name = file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) return service.replace(boardId, csv.parse(bytes));
        if (name.endsWith(".xlsx")) return service.replace(boardId, xlsx.parse(bytes));
        throw new RosterInputException("UNSUPPORTED_FILE_TYPE");
    }

    private static byte[] read(MultipartFile file) {
        try (var input = file.getInputStream()) {
            var bytes = input.readNBytes(RosterCsvParser.MAX_BYTES + 1);
            if (bytes.length > RosterCsvParser.MAX_BYTES) throw new RosterInputException("FILE_TOO_LARGE");
            return bytes;
        } catch (IOException exception) {
            throw new RosterInputException("INVALID_MULTIPART");
        }
    }
}

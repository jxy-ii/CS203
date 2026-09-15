package mygrant.ingestion;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.dto.IngestionResponse;

@RestController
@RequestMapping("/ingestion")
public class IngestionController {

    private final PolicyIngestionService ingestionService;

    public IngestionController(PolicyIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public IngestionResponse ingest(@Valid @RequestBody IngestPolicyRequest request) {
        return ingestionService.ingest(request);
    }
}

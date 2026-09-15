package mygrant.ingestion;

public class PolicyAlreadyExistsException extends RuntimeException {
    public PolicyAlreadyExistsException(String externalId) {
        super("Policy document " + externalId
                + " already exists with different content; versioned updates are not implemented yet");
    }
}

package dev.changeassurance.persistence;

/** Database failure or immutable identity conflict; identical publication retries are safe. */
public class EvidencePersistenceException extends RuntimeException {
    public EvidencePersistenceException(String message) {
        super(message);
    }

    public EvidencePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}

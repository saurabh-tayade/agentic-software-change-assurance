package dev.changeassurance.persistence;

/** Stored publication consistency failure; never a successful empty read. */
public final class PublicationIntegrityException extends EvidencePersistenceException {
    public PublicationIntegrityException() {
        super("Stored evidence publication failed integrity verification");
    }
}

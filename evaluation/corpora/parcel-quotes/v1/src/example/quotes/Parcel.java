package example.quotes;

public record Parcel(int weightGrams, boolean remoteDestination) {
    public Parcel {
        if (weightGrams <= 0) {
            throw new IllegalArgumentException("Weight must be positive");
        }
    }
}

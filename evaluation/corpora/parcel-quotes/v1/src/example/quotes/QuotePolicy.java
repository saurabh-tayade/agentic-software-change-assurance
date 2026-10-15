package example.quotes;

public final class QuotePolicy {
    public int priceCents(Parcel parcel) {
        int base = parcel.weightGrams() <= 1000 ? 500 : 900;
        return base + (parcel.remoteDestination() ? 300 : 0);
    }
}

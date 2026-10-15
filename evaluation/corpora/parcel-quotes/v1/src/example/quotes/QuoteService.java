package example.quotes;

public final class QuoteService {
    private final QuotePolicy policy = new QuotePolicy();

    public int quote(int weightGrams, boolean remoteDestination) {
        return policy.priceCents(new Parcel(weightGrams, remoteDestination));
    }
}

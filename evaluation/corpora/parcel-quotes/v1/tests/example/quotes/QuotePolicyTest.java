package example.quotes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class QuotePolicyTest {
    @Test
    void remoteSmallParcelIncludesSurcharge() {
        assertEquals(800, new QuotePolicy().priceCents(new Parcel(1000, true)));
    }
}

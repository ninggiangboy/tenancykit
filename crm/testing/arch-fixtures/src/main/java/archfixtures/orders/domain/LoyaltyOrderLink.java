package archfixtures.orders.domain;

/** Violates no-class-names-another-app: a crm class named after the loyalty app. */
public class LoyaltyOrderLink {

    public String app() {
        return "points";
    }
}

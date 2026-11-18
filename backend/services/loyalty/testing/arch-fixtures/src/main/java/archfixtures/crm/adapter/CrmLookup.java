package archfixtures.customer.adapter;

/**
 * Deliberately named and packaged after the app this build must never mention, so the rule that forbids it can be
 * shown to fail. It is a fixture: it is on the test classpath only and nothing production references it.
 */
public class CrmLookup {

    public String answer() {
        return "looked up elsewhere";
    }
}

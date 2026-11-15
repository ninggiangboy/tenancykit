package archfixtures.money.application;

/** The ledger repository the append-only rule is pointed at; the sample tenant system has no ledger. */
public interface LedgerRepository {

    void append(String entry);

    void delete(String entryId);
}

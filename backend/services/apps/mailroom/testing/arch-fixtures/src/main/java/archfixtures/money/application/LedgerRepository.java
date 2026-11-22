package archfixtures.money.application;

/** The ledger repository the append-only rule is pointed at. */
public interface LedgerRepository {

    void append(String entry);

    void delete(String entryId);
}

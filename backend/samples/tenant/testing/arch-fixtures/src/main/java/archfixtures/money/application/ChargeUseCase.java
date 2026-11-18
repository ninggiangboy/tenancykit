package archfixtures.money.application;

/** Violates use-case-opens-a-unit-of-work: a use case that never depends on UnitOfWork. */
public class ChargeUseCase {
    public void charge() {}
}

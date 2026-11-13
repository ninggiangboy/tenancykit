package dev.ngb.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorTaxonomyTest {

    @Test
    void badRequestCarriesTheMessageAndIsARuntimeException() {
        BadRequestException e = new BadRequestException("amount is not a number");

        assertThat(e).isInstanceOf(RuntimeException.class);
        assertThat(e.getMessage()).isEqualTo("amount is not a number");
    }

    @Test
    void paymentRequiredCarriesTheMessageAndIsARuntimeException() {
        PaymentRequiredException e = new PaymentRequiredException("the wallet cannot cover this amount");

        assertThat(e).isInstanceOf(RuntimeException.class);
        assertThat(e.getMessage()).isEqualTo("the wallet cannot cover this amount");
    }
}

package com.imin.iminapi.audience.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MailboxSyntaxTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "fan@example.com|false",
            "fan@sub.example.org|false",
            "fan@example.net|false",
            "a@b|false",
            "a b@x.fr|false",
            "x@host.test|false",
            "x@shop.example|false",
            "x@box.invalid|false",
            "x@printer.local|false",
            "a..b@x.fr|false",
            ".a@x.fr|false",
            "a@b@x.fr|false",
            "a@-x.fr|false",
            "a@10.0.0.1|false",
            "a@b.fr\\nBcc: x@y.fr|false",
            "fan@gmail.com|true",
            "fan+night@gmail.com|true",
            "фан@пошта.укр|true",
            "o'neil@club.ie|true",
            "fan@notexample.com|true"
    })
    void rejectsUndeliverableAndAcceptsRealAddresses(String address, boolean deliverable) {
        assertThat(MailboxSyntax.isDeliverable(address.replace("\\n", "\n"))).isEqualTo(deliverable);
    }
}

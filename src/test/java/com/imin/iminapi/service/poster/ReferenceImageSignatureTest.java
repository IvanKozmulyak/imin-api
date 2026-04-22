package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceImageSignatureTest {

    @Test
    void signature_isOrderIndependent() {
        byte[] a = new byte[]{1, 2, 3};
        byte[] b = new byte[]{4, 5, 6};
        String sig1 = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img_a.png", a),
                new ReferenceImageLibrary.SignatureInput("img_b.png", b)));
        String sig2 = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img_b.png", b),
                new ReferenceImageLibrary.SignatureInput("img_a.png", a)));
        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    void signature_changesWhenContentChanges() {
        byte[] orig    = new byte[]{1, 2, 3};
        byte[] changed = new byte[]{1, 2, 4};
        String sigOrig = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img.png", orig)));
        String sigChanged = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img.png", changed)));
        assertThat(sigOrig).isNotEqualTo(sigChanged);
    }

    @Test
    void signature_changesWhenReferenceAdded() {
        byte[] a = new byte[]{1, 2, 3};
        byte[] b = new byte[]{4, 5, 6};
        String sig1 = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img_a.png", a)));
        String sig2 = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img_a.png", a),
                new ReferenceImageLibrary.SignatureInput("img_b.png", b)));
        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    void signature_isHexEncoded64Chars() {
        String sig = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img.png", new byte[]{0})));
        assertThat(sig).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void signature_emptyListIsValid() {
        String sig = ReferenceImageLibrary.imageSignature(List.of());
        assertThat(sig).hasSize(64);
    }
}

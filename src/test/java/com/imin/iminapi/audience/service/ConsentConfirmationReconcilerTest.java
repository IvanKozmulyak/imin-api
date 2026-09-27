package com.imin.iminapi.audience.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsentConfirmationReconcilerTest {

    @Test
    @SuppressWarnings("unchecked")
    void startupPassFailure_isLoggedNotThrown() {
        ConsentConfirmationReconciler proxied = mock(ConsentConfirmationReconciler.class);
        when(proxied.run()).thenThrow(new IllegalStateException("db down"));
        ObjectProvider<ConsentConfirmationReconciler> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(proxied);
        ConsentConfirmationReconciler reconciler = new ConsentConfirmationReconciler(mock(JdbcTemplate.class), self);

        assertThatCode(reconciler::onStartup).doesNotThrowAnyException();
        assertThatCode(reconciler::periodic).doesNotThrowAnyException();
        verify(proxied, org.mockito.Mockito.times(2)).run();
    }
}

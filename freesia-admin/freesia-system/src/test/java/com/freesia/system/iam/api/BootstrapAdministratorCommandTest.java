package com.freesia.system.iam.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapAdministratorCommandTest {
    @Test
    void emptyAndWhitespaceInputReportTheProblemAndPromptAgainWithoutExposingSecrets() {
        char[] empty = {};
        char[] whitespace = " \t ".toCharArray();
        char[] password = {'1'};
        var attempts = new ArrayDeque<>(List.of(empty, whitespace, password));
        var errors = new ArrayList<String>();

        char[] result = BootstrapAdministratorCommand.readPassword(attempts::removeFirst, errors::add);

        assertThat(result).isSameAs(password);
        assertThat(attempts).isEmpty();
        assertThat(errors).containsExactly(
                "Password must not be empty or whitespace. Try again, or press Ctrl+C to cancel.",
                "Password must not be empty or whitespace. Try again, or press Ctrl+C to cancel.");
        assertThat(whitespace).containsOnly('\0');
    }

    @Test
    void anyNonblankPasswordIsAcceptedWithoutLengthOrCompositionChecks() {
        for (String value : List.of("1", " 1 ", "x".repeat(300))) {
            char[] password = value.toCharArray();
            var errors = new ArrayList<String>();

            assertThat(BootstrapAdministratorCommand.readPassword(() -> password, errors::add))
                    .isSameAs(password).containsExactly(value.toCharArray());
            assertThat(errors).isEmpty();
        }
    }

    @Test
    void repeatedEmptyInputDoesNotImposeAnAttemptLimit() {
        var attempts = new ArrayDeque<char[]>();
        for (int i = 0; i < 20; i++) attempts.add(new char[0]);
        char[] password = {'1'};
        attempts.add(password);
        var errors = new ArrayList<String>();

        assertThat(BootstrapAdministratorCommand.readPassword(attempts::removeFirst, errors::add))
                .isSameAs(password);
        assertThat(errors).hasSize(20);
        assertThat(attempts).isEmpty();
    }

    @Test
    void closedInputCancelsWithoutLoopingOrInventingAPassword() {
        var errors = new ArrayList<String>();

        assertThat(BootstrapAdministratorCommand.readPassword(() -> null, errors::add)).isNull();
        assertThat(errors).isEmpty();
    }
}

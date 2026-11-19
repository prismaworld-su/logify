package su.prismaworld.logify.auth;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Коды регистрации вида {@code AZA-AXF}: две группы по три латинские буквы.
 */
public final class RegistrationCodes {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int GROUP_LENGTH = 3;
    private static final int LENGTH = GROUP_LENGTH * 2;

    private final RandomGenerator random;

    public RegistrationCodes() {
        this(new SecureRandom());
    }

    RegistrationCodes(RandomGenerator random) {
        this.random = random;
    }

    public String generate() {
        StringBuilder code = new StringBuilder(LENGTH + 1);
        for (int i = 0; i < LENGTH; i++) {
            if (i == GROUP_LENGTH) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * Приводит введённый код к виду {@code ABC-DEF}: регистр не важен, дефис можно не ставить.
     *
     * @return пусто, если строка не похожа на код
     */
    public static Optional<String> normalize(String input) {
        String letters = input.strip().toUpperCase(Locale.ROOT).replace("-", "");
        if (letters.length() != LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < LENGTH; i++) {
            if (ALPHABET.indexOf(letters.charAt(i)) < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(letters.substring(0, GROUP_LENGTH) + "-" + letters.substring(GROUP_LENGTH));
    }
}

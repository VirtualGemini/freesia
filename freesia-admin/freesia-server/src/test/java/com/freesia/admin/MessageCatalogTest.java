package com.freesia.admin;

import com.freesia.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.AttributedCharacterIterator;
import java.text.Format;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The resource contract: missing keys and broken parameter translations fail the build. */
class MessageCatalogTest {
    private static final Pattern PARAMETERS = Pattern.compile("\\{([^{}]+)}");

    @Test
    void productionCatalogsHaveCompleteChineseAndEnglishMessagesWithMatchingParameters() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        Map<String, Properties> catalogs = new LinkedHashMap<>();
        for (Resource resource : resolver.getResources("classpath*:i18n/**/messages*.properties")) {
            String location = resource.getURL().toExternalForm();
            Properties messages = new Properties() {
                @Override
                public synchronized Object put(Object key, Object value) {
                    assertThat(containsKey(key)).as("duplicate key %s in %s", key, location).isFalse();
                    return super.put(key, value);
                }
            };
            try (var reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                messages.load(reader);
            }
            catalogs.put(location, messages);
        }
        assertThat(catalogs).isNotEmpty();
        Map<String, String> owners = new LinkedHashMap<>();
        for (var entry : catalogs.entrySet()) {
            if (entry.getKey().endsWith("_en.properties")) {
                assertThat(catalogs).containsKey(entry.getKey().replace("_en.properties", ".properties"));
                continue;
            }
            String location = entry.getKey();
            assertThat(location).endsWith("/messages.properties");
            Properties chinese = entry.getValue();
            Properties english = catalogs.get(location.replace(".properties", "_en.properties"));
            assertThat(english).as("English catalog for %s", location).isNotNull();
            assertThat(english.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(chinese.stringPropertyNames());
            for (String key : chinese.stringPropertyNames()) {
                assertThat(key).matches("(common|app|iam|file|notification|audit|dashboard)\\.(error|validation|message|export)\\.[a-z0-9.-]+");
                assertThat(owners.put(key, location)).as("key %s must have one owning catalog", key).isNull();
                assertThat(chinese.getProperty(key)).as("Chinese value of %s", key).isNotBlank().isNotEqualTo(key);
                assertThat(english.getProperty(key)).as("English value of %s", key).isNotBlank().isNotEqualTo(key);
                assertThat(parameters(english.getProperty(key))).as("parameters of %s", key)
                        .containsExactlyInAnyOrderElementsOf(parameters(chinese.getProperty(key)));
                if (!key.contains(".validation.")) {
                    assertFormattedParameters(key, chinese.getProperty(key), Locale.CHINESE);
                    assertFormattedParameters(key, english.getProperty(key), Locale.ENGLISH);
                }
            }
        }
        assertThat(owners.keySet()).containsAll(Arrays.stream(CommonErrorCode.values()).map(CommonErrorCode::i18nKey).toList());
    }

    private static Set<String> parameters(String message) {
        return PARAMETERS.matcher(message).results()
                .map(match -> match.group(1).split(",", 2)[0].strip())
                .collect(Collectors.toSet());
    }

    private static void assertFormattedParameters(String key, String pattern, Locale locale) {
        var format = new MessageFormat(pattern, locale);
        int size = format.getFormatsByArgumentIndex().length;
        // Let MessageFormat interpret quoting; neutralize value-specific formats and observe which
        // arguments really appear, rather than reimplementing its apostrophe grammar.
        format.setFormatsByArgumentIndex(new Format[size]);
        Object[] arguments = new Object[size];
        Arrays.fill(arguments, "argument");
        var rendered = format.formatToCharacterIterator(arguments);
        Set<String> recognized = new LinkedHashSet<>();
        for (char character = rendered.first(); character != AttributedCharacterIterator.DONE; character = rendered.next()) {
            Object argument = rendered.getAttribute(MessageFormat.Field.ARGUMENT);
            if (argument != null) {
                recognized.add(argument.toString());
            }
        }
        assertThat(recognized).as("effective MessageFormat parameters of %s (%s)", key, locale)
                .containsExactlyInAnyOrderElementsOf(parameters(pattern));
    }
}

package com.orqix.shared.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds {@code orqix.*} {@link Path} properties as plain file-system paths. Without it Spring's default conversion treats
 * a relative value such as {@code ../../deploy/compose/secrets} (the dev profile's secrets directory) as a classpath
 * resource and fails at startup.
 */
@Component
@ConfigurationPropertiesBinding
class FileSystemPathConverter implements Converter<String, Path> {

    @Override
    public Path convert(String source) {
        return Path.of(source.trim());
    }
}

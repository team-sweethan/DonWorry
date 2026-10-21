package com.donworry;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

// mariadb 태그 테스트가 붙는 실제 MariaDB — docker-compose.yml과 같은 버전·문자셋·UTC
// 컨테이너는 JVM당 하나만 띄우고, Spring 데이터소스를 이 컨테이너로 바꿔 끼운다
@TestConfiguration(proxyBeanMethods = false)
public class MariaDbTestContainerConfig {

    // docker-compose.yml의 이미지와 맞춘다. 개발 DB를 올리면 여기도 같이 올린다
    static final DockerImageName IMAGE = DockerImageName.parse("mariadb:11.8");

    static final MariaDBContainer CONTAINER = start();

    private static MariaDBContainer start() {
        MariaDBContainer container = new MariaDBContainer(IMAGE)
                .withDatabaseName("donworry")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
        container.start();
        return container;
    }

    @Bean
    JdbcConnectionDetails jdbcConnectionDetails() {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return CONTAINER.getUsername();
            }

            @Override
            public String getPassword() {
                return CONTAINER.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return CONTAINER.getJdbcUrl();
            }
        };
    }
}

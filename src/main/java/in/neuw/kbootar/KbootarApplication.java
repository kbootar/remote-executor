package in.neuw.kbootar;

import in.neuw.kbootar.tls.DomainTlsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DomainTlsProperties.class)
public class KbootarApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbootarApplication.class, args);
    }
}

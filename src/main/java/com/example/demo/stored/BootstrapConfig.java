package com.example.demo.stored;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class BootstrapConfig {
    @Bean
    @ConditionalOnProperty(name="invoice.bootstrap.enabled",havingValue="true")
    ApplicationRunner bootstrap(AccountService accounts, Environment env, ConfigurableApplicationContext context) {
        return args -> {
            accounts.bootstrap(env.getRequiredProperty("invoice.bootstrap.company-code"),
                env.getRequiredProperty("invoice.bootstrap.company-name"),
                env.getRequiredProperty("invoice.bootstrap.email"),
                env.getRequiredProperty("invoice.bootstrap.password"));
            System.out.println("Company and administrator created. Bootstrap process is stopping.");
            SpringApplication.exit(context);
        };
    }
}


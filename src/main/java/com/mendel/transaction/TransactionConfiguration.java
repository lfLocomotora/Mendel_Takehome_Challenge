package com.mendel.transaction;

import com.mendel.transaction.application.TransactionService;
import com.mendel.transaction.concurrency.TransactionAccessCoordinator;
import com.mendel.transaction.storage.InMemoryTransactionStore;
import com.mendel.transaction.storage.TransactionStore;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TransactionConfiguration {
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> encodedTypePathCustomizer() {
    return factory ->
        factory.addConnectorCustomizers(connector -> {
          // Preserve encoded separators until Spring decodes the individual path segment.
          connector.setEncodedSolidusHandling("passthrough");
          connector.setEncodedReverseSolidusHandling("passthrough");
        });
  }

  @Bean
  TransactionStore transactionStore() {
    return new InMemoryTransactionStore();
  }

  @Bean
  TransactionAccessCoordinator transactionAccessCoordinator() {
    return new TransactionAccessCoordinator();
  }

  @Bean
  TransactionService transactionService(
      TransactionStore store, TransactionAccessCoordinator access) {
    return new TransactionService(store, access);
  }
}

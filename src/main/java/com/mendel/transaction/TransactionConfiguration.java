package com.mendel.transaction;

import com.mendel.transaction.application.TransactionService;
import com.mendel.transaction.concurrency.TransactionAccessCoordinator;
import com.mendel.transaction.storage.InMemoryTransactionStore;
import com.mendel.transaction.storage.TransactionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TransactionConfiguration {
  @Bean
  TransactionStore transactionStore() {
    return new InMemoryTransactionStore();
  }

  @Bean
  TransactionAccessCoordinator transactionAccessCoordinator() {
    return new TransactionAccessCoordinator();
  }

  @Bean
  TransactionService transactionService(TransactionStore store, TransactionAccessCoordinator access) {
    return new TransactionService(store, access);
  }
}

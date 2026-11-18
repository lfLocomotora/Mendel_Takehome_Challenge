package com.mendel.transaction.domain;

public record Transaction(long id, double amount, String type, Long parentId) {}

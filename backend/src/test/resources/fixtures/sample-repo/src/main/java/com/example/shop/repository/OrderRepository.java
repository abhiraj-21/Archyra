package com.example.shop.repository;

import com.example.shop.model.Order;

public interface OrderRepository {

	Order save(Order order);

	Order find(String id);
}

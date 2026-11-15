package com.example.shop.repository;

import java.util.HashMap;
import java.util.Map;

import com.example.shop.model.Order;

public class InMemoryOrderRepository implements OrderRepository {

	private final Map<String, Order> store = new HashMap<>();

	@Override
	public Order save(Order order) {
		store.put(order.getId(), order);
		return order;
	}

	@Override
	public Order find(String id) {
		return store.get(id);
	}
}

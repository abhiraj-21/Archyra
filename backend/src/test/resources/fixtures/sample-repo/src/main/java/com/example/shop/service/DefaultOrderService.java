package com.example.shop.service;

import com.example.shop.model.Order;
import com.example.shop.model.OrderRequest;
import com.example.shop.repository.OrderRepository;

public class DefaultOrderService implements OrderService {

	private final OrderRepository repository;

	public DefaultOrderService(OrderRepository repository) {
		this.repository = repository;
	}

	@Override
	public Order place(OrderRequest request) {
		Order order = new Order(request.id());
		order.addItem(request.item());
		return repository.save(order);
	}
}

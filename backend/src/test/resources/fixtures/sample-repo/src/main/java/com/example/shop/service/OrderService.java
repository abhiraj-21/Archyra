package com.example.shop.service;

import com.example.shop.model.Order;
import com.example.shop.model.OrderRequest;

public interface OrderService {

	Order place(OrderRequest request);
}

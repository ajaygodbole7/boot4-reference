package com.example.boot4ref.common.fixture;

import com.example.boot4ref.order.rest.OrderCreateRequest;
import com.example.boot4ref.order.rest.OrderLineRequest;
import com.example.boot4ref.order.service.OrderService;
import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.ProductStatus;
import com.example.boot4ref.product.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds the database with sample data on startup when the "dev" profile is active.
 * Creates 10 products (activated) and 5 orders.
 */
@Profile("dev")
@Component
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final String[][] PRODUCTS = {
            {"Ergonomic Keyboard", "Mechanical switches with split design", "149.99", "42"},
            {"Wireless Mouse", "Bluetooth 5.0 with USB-C charging", "59.99", "120"},
            {"USB-C Hub", "7-port hub with HDMI and Ethernet", "39.99", "85"},
            {"Monitor Stand", "Adjustable height aluminium stand", "79.99", "30"},
            {"Webcam HD", "1080p with auto-focus and noise cancellation", "89.99", "65"},
            {"Desk Lamp", "LED with adjustable colour temperature", "34.99", "200"},
            {"Cable Organiser", "Silicone clips for desk cable management", "12.99", "300"},
            {"Laptop Sleeve", "Neoprene sleeve for 14-inch laptops", "24.99", "150"},
            {"Bluetooth Speaker", "Portable waterproof speaker", "44.99", "75"},
            {"Charging Pad", "Qi wireless charging pad 15W", "29.99", "180"},
    };

    private final ProductRepository productRepository;
    private final OrderService orderService;

    public DevDataSeeder(ProductRepository productRepository,
                         OrderService orderService) {
        this.productRepository = productRepository;
        this.orderService = orderService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (productRepository.count() > 0) {
            log.info("Database already seeded, skipping");
            return;
        }

        log.info("Seeding dev data: {} products + 5 orders", PRODUCTS.length);

        List<Product> products = new ArrayList<>();
        for (String[] row : PRODUCTS) {
            Product product = Product.builder()
                    .name(row[0])
                    .description(row[1])
                    .price(new BigDecimal(row[2]))
                    .stock(Integer.parseInt(row[3]))
                    .status(ProductStatus.ACTIVE)
                    .build();
            products.add(productRepository.save(product));
            log.debug("Seeded product id={} name={}", product.getId(), product.getName());
        }

        for (int i = 0; i < 5; i++) {
            Product target = products.get(i % products.size());
            var response = orderService.create(
                    new OrderCreateRequest(List.of(new OrderLineRequest(target.getId(), i + 1))),
                    "dev-seed-" + i);
            log.debug("Seeded order id={}", response.id());
        }

        log.info("Dev data seeding complete");
    }
}

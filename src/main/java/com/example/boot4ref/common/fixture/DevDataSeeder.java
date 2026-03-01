package com.example.boot4ref.common.fixture;

import com.example.boot4ref.order.service.OrderService;
import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.repository.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds the database with realistic sample data on startup when the "dev" profile is active.
 * Creates 10 products (activated) and 5 orders.
 */
@Profile("dev")
@Component
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final int PRODUCT_SEED_COUNT = 10;
    private static final int ORDER_SEED_COUNT = 5;

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

        log.info("Seeding dev data: {} products + {} orders", PRODUCT_SEED_COUNT, ORDER_SEED_COUNT);

        // Create products and activate them — keep references for order seeding
        List<Product> products = new ArrayList<>();
        for (int i = 0; i < PRODUCT_SEED_COUNT; i++) {
            Product product = productRepository.save(ProductFixtures.randomActiveProduct());
            products.add(product);
            log.debug("Seeded product id={} name={}", product.getId(), product.getName());
        }

        // Create orders against saved products
        for (int i = 0; i < ORDER_SEED_COUNT; i++) {
            Product target = products.get(i % products.size());
            var response = orderService.create(
                    OrderFixtures.randomOrderCreateRequest(target.getId()),
                    "dev-seed-" + i);
            log.debug("Seeded order id={}", response.id());
        }

        log.info("Dev data seeding complete");
    }
}

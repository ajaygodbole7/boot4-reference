package com.example.boot4ref.product.service;

import com.example.boot4ref.AbstractIntegrationTest;
import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.ProductStatus;
import com.example.boot4ref.product.repository.ProductRepository;
import com.example.boot4ref.product.rest.ProductPatchRequest;
import com.example.boot4ref.product.rest.ProductUpdateRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Integration test proving @Retryable on ProductService.update() and patch()
 * fires on ObjectOptimisticLockingFailureException and retries in a new transaction.
 *
 * <p>Strategy: spy on ProductRepository to intercept saveAndFlush() calls and always throw
 * ObjectOptimisticLockingFailureException. Verify the number of retry attempts:
 * - Without @Retryable: saveAndFlush() is called once (exception propagates immediately).
 * - With @Retryable(maxAttempts=3): saveAndFlush() is called 3 times before giving up.
 *
 * <p>RED: verify(times(3)) fails — only 1 saveAndFlush() call without @Retryable.
 * GREEN: verify(times(3)) passes — @Retryable fires, exhausting all 3 attempts.
 */
class ProductServiceRetryIT extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * Proves update() attempts all @Retryable maxAttempts on repeated OptimisticLockFailure.
     *
     * <p>Expected call count:
     * - RED (no @Retryable): 1 call — fails with times(3) assertion
     * - GREEN (@Retryable maxAttempts=3): 3 calls — passes
     */
    @Test
    void update_shouldAttemptRetries_onRepeatedOptimisticLockFailure() {
        // GIVEN: a saved product
        Product saved = transactionTemplate.execute(status ->
                productRepository.save(Product.builder()
                        .name("Retry Target")
                        .description("desc")
                        .price(new BigDecimal("10.00"))
                        .stock(5)
                        .status(ProductStatus.DRAFT)
                        .build())
        );
        assertThat(saved).isNotNull();
        Mockito.reset(productRepository);

        // Spy: ALL saveAndFlush() calls throw — exhausts all retry attempts
        Mockito.doAnswer(invocation -> {
            throw new ObjectOptimisticLockingFailureException(Product.class, saved.getId());
        }).when(productRepository).saveAndFlush(any(Product.class));

        ProductUpdateRequest request = new ProductUpdateRequest(
                "Will Not Succeed",
                "desc",
                new BigDecimal("20.00"),
                10
        );

        // WHEN / THEN: exception propagates after all retry attempts exhausted
        assertThatThrownBy(() -> productService.update(saved.getId(), request))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // GREEN (with @Retryable maxAttempts=3): saveAndFlush() called 3 times
        // RED (without @Retryable): saveAndFlush() called 1 time — this assertion fails in RED
        verify(productRepository, times(3)).saveAndFlush(any(Product.class));
    }

    /**
     * Proves patch() attempts all @Retryable maxAttempts on repeated OptimisticLockFailure.
     *
     * <p>Expected call count:
     * - RED (no @Retryable): 1 call — fails with times(3) assertion
     * - GREEN (@Retryable maxAttempts=3): 3 calls — passes
     */
    @Test
    void patch_shouldAttemptRetries_onRepeatedOptimisticLockFailure() {
        // GIVEN: a saved product
        Product saved = transactionTemplate.execute(status ->
                productRepository.save(Product.builder()
                        .name("Patch Retry Target")
                        .description("patch desc")
                        .price(new BigDecimal("15.00"))
                        .stock(3)
                        .status(ProductStatus.DRAFT)
                        .build())
        );
        assertThat(saved).isNotNull();
        Mockito.reset(productRepository);

        // Spy: ALL saveAndFlush() calls throw
        Mockito.doAnswer(invocation -> {
            throw new ObjectOptimisticLockingFailureException(Product.class, saved.getId());
        }).when(productRepository).saveAndFlush(any(Product.class));

        ProductPatchRequest patchRequest = new ProductPatchRequest(
                "Patch Will Not Succeed",
                null,
                null,
                null
        );

        // WHEN / THEN: exception propagates after all retry attempts exhausted
        assertThatThrownBy(() -> productService.patch(saved.getId(), patchRequest))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // GREEN (with @Retryable maxAttempts=3): saveAndFlush() called 3 times
        // RED (without @Retryable): saveAndFlush() called 1 time — this assertion fails in RED
        verify(productRepository, times(3)).saveAndFlush(any(Product.class));
    }
}

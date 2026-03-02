package com.example.boot4ref.order.service;

import com.example.boot4ref.config.ApplicationProperties;
import com.example.boot4ref.order.repository.OrderRepository;
import com.example.boot4ref.outbox.OutboxPublisher;
import com.example.boot4ref.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderService}.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OutboxPublisher outboxPublisher;

    @SuppressWarnings("unchecked")
    private OrderService createService(int defaultPageSize, int maxPageSize) {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getPagination().setDefaultPageSize(defaultPageSize);
        properties.getPagination().setMaxPageSize(maxPageSize);
        return new OrderService(orderRepository, productRepository, outboxPublisher, properties);
    }

    @Test
    void shouldCapLimitToMaxPageSize() {
        OrderService service = createService(20, 100);
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listFiltered(null, null, 500);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void shouldUseDefaultPageSizeWhenLimitIsNull() {
        OrderService service = createService(20, 100);
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listFiltered(null, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void shouldPassLimitThroughWhenUnderMax() {
        OrderService service = createService(20, 100);
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listFiltered(null, null, 50);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }
}

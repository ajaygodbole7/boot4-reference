package com.example.boot4ref.order.service;

import com.example.boot4ref.config.ApplicationProperties;
import com.example.boot4ref.order.Order;
import com.example.boot4ref.order.OrderLine;
import com.example.boot4ref.order.OrderStatus;
import com.example.boot4ref.order.event.DomainEvent;
import com.example.boot4ref.order.exception.DuplicateLineItemException;
import com.example.boot4ref.order.exception.InsufficientStockException;
import com.example.boot4ref.order.exception.UnorderableProductException;
import com.example.boot4ref.order.repository.OrderRepository;
import com.example.boot4ref.order.rest.OrderCreateRequest;
import com.example.boot4ref.order.rest.OrderLineRequest;
import com.example.boot4ref.outbox.OutboxWriter;
import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.ProductStatus;
import com.example.boot4ref.product.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

/**
 * Unit tests for {@link OrderService}.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked") // Mockito any(Specification.class) returns raw type
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OutboxWriter outboxWriter;

    private OrderService createService(int defaultPageSize, int maxPageSize) {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getPagination().setDefaultPageSize(defaultPageSize);
        properties.getPagination().setMaxPageSize(maxPageSize);
        return new OrderService(orderRepository, productRepository, outboxWriter, properties);
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

    @Test
    void shouldRejectOrderForDraftProduct() {
        OrderService service = createService(20, 100);
        Product draft = Product.builder()
                .name("Draft Widget").price(new BigDecimal("10.00"))
                .stock(100).status(ProductStatus.DRAFT).build();
        when(productRepository.findWithLockById(1L)).thenReturn(Optional.of(draft));

        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(1L, 1)));

        assertThatThrownBy(() -> service.create(request, null))
                .isInstanceOf(UnorderableProductException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void shouldRejectOrderWithDuplicateProductIds() {
        OrderService service = createService(20, 100);

        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(1L, 2), new OrderLineRequest(1L, 3)));

        assertThatThrownBy(() -> service.create(request, null))
                .isInstanceOf(DuplicateLineItemException.class)
                .hasMessageContaining("1");
    }

    @Test
    void shouldCreateSingleLineOrderWithStockDecrementAndOutboxEvent() {
        OrderService service = createService(20, 100);
        Product widget = Product.builder()
                .name("Widget").price(new BigDecimal("25.00"))
                .stock(100).status(ProductStatus.ACTIVE).build();
        when(productRepository.findWithLockById(1L)).thenReturn(Optional.of(widget));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderCreateResult result = service.create(
                new OrderCreateRequest(List.of(new OrderLineRequest(1L, 3))), null);

        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.order().totalAmount()).isEqualByComparingTo("75.00");
        assertThat(result.order().items()).hasSize(1);
        assertThat(widget.getStock()).isEqualTo(97);

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).stageEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(DomainEvent.OrderPlaced.class);
    }

    @Test
    void shouldCreateMultiLineOrderWithCorrectTotalAndStockDecrements() {
        OrderService service = createService(20, 100);
        Product widgetA = Product.builder()
                .name("Widget A").price(new BigDecimal("10.00"))
                .stock(50).status(ProductStatus.ACTIVE).build();
        Product widgetB = Product.builder()
                .name("Widget B").price(new BigDecimal("20.00"))
                .stock(30).status(ProductStatus.ACTIVE).build();
        when(productRepository.findWithLockById(1L)).thenReturn(Optional.of(widgetA));
        when(productRepository.findWithLockById(2L)).thenReturn(Optional.of(widgetB));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderCreateResult result = service.create(
                new OrderCreateRequest(List.of(
                        new OrderLineRequest(1L, 5),
                        new OrderLineRequest(2L, 2))), null);

        assertThat(result.newlyCreated()).isTrue();
        // 5 * 10.00 + 2 * 20.00 = 90.00
        assertThat(result.order().totalAmount()).isEqualByComparingTo("90.00");
        assertThat(result.order().items()).hasSize(2);
        assertThat(widgetA.getStock()).isEqualTo(45);
        assertThat(widgetB.getStock()).isEqualTo(28);
        verify(outboxWriter).stageEvent(any(DomainEvent.OrderPlaced.class));
    }

    @Test
    void shouldRejectOrderWhenInsufficientStock() {
        OrderService service = createService(20, 100);
        Product widget = Product.builder()
                .name("Widget").price(new BigDecimal("10.00"))
                .stock(2).status(ProductStatus.ACTIVE).build();
        when(productRepository.findWithLockById(1L)).thenReturn(Optional.of(widget));

        OrderCreateRequest request = new OrderCreateRequest(
                List.of(new OrderLineRequest(1L, 10)));

        assertThatThrownBy(() -> service.create(request, null))
                .isInstanceOf(InsufficientStockException.class);
        // Stock should not be decremented on failure
        assertThat(widget.getStock()).isEqualTo(2);
        verify(outboxWriter, never()).stageEvent(any());
    }

    @Test
    void shouldReturnExistingOrderWhenIdempotencyKeyMatches() {
        OrderService service = createService(20, 100);
        Order existing = Order.builder().status(OrderStatus.PLACED)
                .idempotencyKey("key-123").build();
        when(orderRepository.findByIdempotencyKey("key-123"))
                .thenReturn(Optional.of(existing));

        OrderCreateResult result = service.create(
                new OrderCreateRequest(List.of(new OrderLineRequest(1L, 1))), "key-123");

        assertThat(result.newlyCreated()).isFalse();
        verify(productRepository, never()).findWithLockById(any());
        verify(outboxWriter, never()).stageEvent(any());
    }

    @Test
    void shouldSkipStockRestoreForDiscontinuedProducts() {
        OrderService service = createService(20, 100);
        Product discontinued = Product.builder()
                .name("Old Widget").price(new BigDecimal("10.00"))
                .stock(0).status(ProductStatus.DISCONTINUED).build();
        setField(discontinued, "id", 100L);
        Product active = Product.builder()
                .name("Widget").price(new BigDecimal("20.00"))
                .stock(10).status(ProductStatus.ACTIVE).build();
        setField(active, "id", 200L);

        // Build an order with two lines
        Order order = Order.builder().status(OrderStatus.PLACED).build();
        order.addOrderLine(OrderLine.builder().product(discontinued).quantity(5)
                .unitPrice(new BigDecimal("10.00")).build());
        order.addOrderLine(OrderLine.builder().product(active).quantity(3)
                .unitPrice(new BigDecimal("20.00")).build());
        order.computeTotal();

        when(orderRepository.findByIdWithLines(1L)).thenReturn(Optional.of(order));
        when(productRepository.findWithLockById(100L)).thenReturn(Optional.of(discontinued));
        when(productRepository.findWithLockById(200L)).thenReturn(Optional.of(active));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.transition(1L, OrderStatus.CANCELLED);

        // Discontinued product stock unchanged, active product stock restored
        assertThat(discontinued.getStock()).isEqualTo(0);
        assertThat(active.getStock()).isEqualTo(13);
    }
}

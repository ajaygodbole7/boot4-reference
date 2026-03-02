package com.example.boot4ref.product.service;

import com.example.boot4ref.config.ApplicationProperties;
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
 * Unit tests for {@link ProductService}.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked") // Mockito any(Specification.class) returns raw type
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ProductService createService(int defaultPageSize, int maxPageSize) {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getPagination().setDefaultPageSize(defaultPageSize);
        properties.getPagination().setMaxPageSize(maxPageSize);
        return new ProductService(productRepository, properties);
    }

    @Test
    void shouldCapLimitToMaxPageSize() {
        ProductService service = createService(20, 100);
        when(productRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listFiltered(null, null, null, null, 500);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void shouldUseDefaultPageSizeWhenLimitIsNull() {
        ProductService service = createService(20, 100);
        when(productRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listFiltered(null, null, null, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void shouldPassLimitThroughWhenUnderMax() {
        ProductService service = createService(20, 100);
        when(productRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listFiltered(null, null, null, null, 50);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }
}

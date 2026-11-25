package br.com.urutau.jigs.catalog;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CatalogProductRepository extends JpaRepository<CatalogProduct, Long> {
    @Query("""
        select distinct product
        from CatalogProduct product
        left join fetch product.variants
        order by product.nameKey
        """)
    List<CatalogProduct> listWithVariants();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from CatalogProduct product where product.id = :id")
    Optional<CatalogProduct> findForUpdate(@Param("id") long id);

    Optional<CatalogProduct> findByNameKey(String nameKey);
}

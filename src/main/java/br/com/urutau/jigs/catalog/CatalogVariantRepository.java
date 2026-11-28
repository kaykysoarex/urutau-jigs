package br.com.urutau.jigs.catalog;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CatalogVariantRepository extends JpaRepository<CatalogVariant, Long> {
    @Query("""
        select variant
        from CatalogVariant variant
        join fetch variant.product
        where variant.id in :ids
        order by variant.id
        """)
    List<CatalogVariant> findWithProducts(@Param("ids") Collection<Long> ids);

    @Query("select distinct v.product.id from CatalogVariant v where v.id in :ids")
    List<Long> affectedModels(@Param("ids") Collection<Long> ids);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from CatalogVariant v where v.id in :ids order by v.id")
    List<CatalogVariant> lockVariants(@Param("ids") Collection<Long> ids);
}

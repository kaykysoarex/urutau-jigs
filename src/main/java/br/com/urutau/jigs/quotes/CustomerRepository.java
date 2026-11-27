
package br.com.urutau.jigs.quotes;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CustomerRepository extends JpaRepository<CommercialCustomer, Long> {
    List<CommercialCustomer> findByNormalizedPhoneOrderByActiveDescNameAscIdAsc(String phone, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CommercialCustomer c where c.id = :id")
    Optional<CommercialCustomer> lockById(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<CommercialCustomer> findByNormalizedPhone(String phone);

    @Query("""
        select c from CommercialCustomer c
        where (:active is null or c.active = :active)
          and (:search = '' or locate(lower(:search), lower(c.name)) > 0
            or locate(lower(:search), lower(c.establishment)) > 0
            or (:digits <> '' and locate(:digits, c.normalizedPhone) > 0)
            or (:phone <> '' and c.normalizedPhone = :phone))
        order by c.active desc, c.name asc, c.id asc
        """)
    Page<CommercialCustomer> search(@Param("search") String search, @Param("digits") String digits,
                                    @Param("phone") String phone, @Param("active") Boolean active, Pageable page);
}

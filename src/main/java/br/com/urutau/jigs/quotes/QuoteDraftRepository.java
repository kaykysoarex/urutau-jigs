
package br.com.urutau.jigs.quotes;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface QuoteDraftRepository extends JpaRepository<QuoteDraft, String> {
    List<QuoteDraft> findByOwnerAndContentIsNotNullOrderByUpdatedAtDescIdDesc(String owner);
    Optional<QuoteDraft> findByIdAndOwnerAndContentIsNotNull(String id, String owner);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from QuoteDraft d where d.id = :id and d.owner = :owner")
    Optional<QuoteDraft> lockOwned(@Param("id") String id, @Param("owner") String owner);
}

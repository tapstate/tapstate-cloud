package io.tapstate.cloud.tls;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface TlsCertificateRepository extends MongoRepository<TlsCertificate, ObjectId> {

    /**
     * Latest certificate stored for the given DNS name whose declared expiry is
     * still in the future. Ordering by descending {@code _id} picks the most
     * recently inserted document because ObjectIds embed a monotonic timestamp.
     */
    Optional<TlsCertificate> findFirstByDomainAndExpiryDateAfterOrderByIdDesc(String domain, Instant now);
}

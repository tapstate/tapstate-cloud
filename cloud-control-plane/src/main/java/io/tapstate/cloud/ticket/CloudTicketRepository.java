package io.tapstate.cloud.ticket;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CloudTicketRepository extends MongoRepository<CloudTicket, ObjectId> {

    Page<CloudTicket> findByOwnerIdOrderByCreatedAtDesc(ObjectId ownerId, Pageable pageable);

    Optional<CloudTicket> findByIdAndOwnerId(ObjectId id, ObjectId ownerId);
}

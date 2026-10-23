package io.tapstate.cloud.event;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;

/** Read surface for generated alerts, scoped to the organization and viewer. */
@Service
@RequiredArgsConstructor
public class AlertQueryService {

    private final AlertRepository alertRepository;
    private final MongoTemplate mongoTemplate;

    public List<AlertRecord> list(String orgId, String viewerEmail, boolean admin, Integer limit) {
        ObjectId org = parseOrgId(orgId);
        Criteria criteria = Criteria.where("orgId").is(org);
        if (!admin) {
            criteria = criteria.and("actor").is(viewerEmail);
        }
        int size = limit == null || limit < 1 ? 50 : Math.min(limit, 100);
        return mongoTemplate.find(
            new Query(criteria)
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(size),
            AlertRecord.class);
    }

    public AlertRecord get(String orgId, String alertId, String viewerEmail, boolean admin) {
        ObjectId org = parseOrgId(orgId);
        ObjectId id = parseObjectId(alertId);
        AlertRecord alert = alertRepository.findByIdAndOrgId(id, org)
            .orElseThrow(() -> new BusinessException(AppErrorCode.ALERT_NOT_FOUND, 404, alertId));
        if (!admin && !viewerEmail.equals(alert.getActor())) {
            throw new BusinessException(AppErrorCode.ALERT_NOT_FOUND, 404, alertId);
        }
        return alert;
    }

    private ObjectId parseOrgId(String orgId) {
        try {
            return new ObjectId(orgId);
        } catch (RuntimeException e) {
            throw new BusinessException(AppErrorCode.ORG_NOT_FOUND, 404, orgId);
        }
    }

    private ObjectId parseObjectId(String value) {
        try {
            return new ObjectId(value);
        } catch (RuntimeException e) {
            throw new BusinessException(AppErrorCode.ALERT_NOT_FOUND, 404, value);
        }
    }
}

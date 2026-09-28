package io.tapstate.cloud.region;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Region service — list available regions for Cluster provisioning.
 * <p>
 * Week 2 seed: ap-southeast-1 only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegionService {

    private final RegionRepository regionRepository;

    /**
     * Seed default region on startup if empty.
     * Week 2: only ap-southeast-1 (Singapore) is open.
     */
    @PostConstruct
    public void seedDefaultRegions() {
        if (regionRepository.count() > 0) {
            return;
        }

        Region defaultRegion = Region.builder()
            .code("ap-southeast-1")
            .name("Asia Pacific (Singapore)")
            .open(true)
            .defaultSpecKey("basic")
            .sortOrder(1)
            .build();

        regionRepository.save(defaultRegion);
        log.info("Seeded default region: {}", defaultRegion.getCode());
    }

    public List<Region> listOpenRegions() {
        return regionRepository.findByOpenTrueOrderBySortOrderAsc();
    }

    public Region getByCode(String code) {
        return regionRepository.findById(code)
            .orElseThrow(() -> new BusinessException(AppErrorCode.REGION_NOT_FOUND, 404, code));
    }
}

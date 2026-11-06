package com.donworry.identity.repository;

import com.donworry.identity.entity.StaffAccessEvent;
import org.springframework.data.repository.Repository;

// 접근 기록은 쌓기만 한다. 삭제 · 수정 메서드를 열지 않는다
public interface StaffAccessEventRepository extends Repository<StaffAccessEvent, Long> {
    StaffAccessEvent saveAndFlush(StaffAccessEvent event);
}

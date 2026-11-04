package com.donworry.identity.repository;

import com.donworry.identity.entity.OperatorAccessDenial;
import org.springframework.data.repository.Repository;

// 조사 원본은 INSERT 전용이다. 보관 정책의 삭제 작업은 조사 참조 연결 후 별도로 구현한다.
public interface OperatorAccessDenialRepository extends Repository<OperatorAccessDenial, Long> {
    OperatorAccessDenial saveAndFlush(OperatorAccessDenial denial);
}

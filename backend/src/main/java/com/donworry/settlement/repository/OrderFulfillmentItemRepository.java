package com.donworry.settlement.repository;

import com.donworry.settlement.entity.OrderFulfillmentItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderFulfillmentItemRepository extends JpaRepository<OrderFulfillmentItem, Long> {
    List<OrderFulfillmentItem> findAllByFulfillmentIdOrderByItemSequenceAsc(Long fulfillmentId);
}

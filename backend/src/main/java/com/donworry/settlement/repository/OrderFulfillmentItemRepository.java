package com.donworry.settlement.repository;

import com.donworry.settlement.entity.OrderFulfillmentItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderFulfillmentItemRepository extends JpaRepository<OrderFulfillmentItem, Long> {
    List<OrderFulfillmentItem> findAllByFulfillmentIdOrderByItemSequenceAsc(Long fulfillmentId);

    @Query("""
            SELECT f.orderId AS orderId, i.productName AS productName,
                   i.quantity AS quantity, i.itemSequence AS itemSequence
            FROM OrderFulfillmentItem i JOIN OrderFulfillment f ON f.fulfillmentId = i.fulfillmentId
            WHERE f.merchantId = :merchant AND f.orderId IN :orders
            ORDER BY f.orderId, i.itemSequence
            """)
    List<SettlementOrderItemProjection> findReportItems(
            @Param("merchant") Long merchant, @Param("orders") List<String> orders);
}

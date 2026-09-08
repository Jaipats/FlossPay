package com.openpay.worker.processor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import com.openpay.shared.model.TransactionEntity;
import com.openpay.shared.repository.TransactionHistoryRepository;
import com.openpay.shared.repository.TransactionRepository;
import com.openpay.worker.client.NpciUpiGatewayClient;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionWorkerConsumerTest {

    @Mock
    private RedisTemplate<Object, Object> redisWorkerTemplate;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private NpciUpiGatewayClient npciUpiGatewayClient;

    @Mock
    private TransactionHistoryRepository transactionHistoryRepository;

    private TransactionWorkerConsumer newConsumer() {
        return new TransactionWorkerConsumer(
                redisWorkerTemplate, transactionRepository, npciUpiGatewayClient, transactionHistoryRepository);
    }

    private TransactionEntity entityWithStatus(String status) {
        TransactionEntity entity = new TransactionEntity();
        entity.setId(42L);
        entity.setSenderUpi("alice@upi");
        entity.setReceiverUpi("bob@upi");
        entity.setAmount(BigDecimal.TEN);
        entity.setStatus(status);
        return entity;
    }

    private Map<Object, Object> payloadFor(long txnId) {
        Map<Object, Object> payload = new HashMap<>();
        payload.put("txnId", txnId);
        return payload;
    }

    @Test
    void doesNotReinvokeGatewayWhenTransactionAlreadyCompleted() {
        when(transactionRepository.findById(42L)).thenReturn(Optional.of(entityWithStatus("completed")));

        boolean result = newConsumer().handleTransaction(payloadFor(42L));

        assertTrue(result);
        verify(npciUpiGatewayClient, never()).initiateUpiPayment(any(), any(), any(), any());
    }

    @Test
    void reportsSuccessAndDoesNotRetryWhenPostGatewayPersistFails() {
        TransactionEntity entity = entityWithStatus("queued");
        when(transactionRepository.findById(42L)).thenReturn(Optional.of(entity));
        when(npciUpiGatewayClient.initiateUpiPayment(anyString(), anyString(), any(BigDecimal.class), any()))
                .thenReturn(true);
        // First save (-> "processing") succeeds; second save (-> "completed") throws,
        // simulating a transient DB failure right after the gateway call succeeded.
        when(transactionRepository.save(entity))
                .thenReturn(entity)
                .thenThrow(new RuntimeException("simulated transient DB failure"));

        boolean result = newConsumer().handleTransaction(payloadFor(42L));

        assertTrue(result, "a persistence failure after a successful gateway call must not be reported as false");
        verify(npciUpiGatewayClient, times(1))
                .initiateUpiPayment(anyString(), anyString(), any(BigDecimal.class), any());
    }

    @Test
    void marksCompletedOnGatewaySuccess() {
        TransactionEntity entity = entityWithStatus("queued");
        when(transactionRepository.findById(42L)).thenReturn(Optional.of(entity));
        when(npciUpiGatewayClient.initiateUpiPayment(anyString(), anyString(), any(BigDecimal.class), any()))
                .thenReturn(true);
        when(transactionRepository.save(entity)).thenReturn(entity);

        boolean result = newConsumer().handleTransaction(payloadFor(42L));

        assertTrue(result);
        assertTrue("completed".equals(entity.getStatus()));
    }

    @Test
    void marksFailedOnGatewayFailure() {
        TransactionEntity entity = entityWithStatus("queued");
        when(transactionRepository.findById(42L)).thenReturn(Optional.of(entity));
        when(npciUpiGatewayClient.initiateUpiPayment(anyString(), anyString(), any(BigDecimal.class), any()))
                .thenReturn(false);
        when(transactionRepository.save(entity)).thenReturn(entity);

        boolean result = newConsumer().handleTransaction(payloadFor(42L));

        assertTrue(!result);
        assertTrue("failed".equals(entity.getStatus()));
    }
}

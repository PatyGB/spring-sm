package com.hareesh.springstatemachine.springstatemachinedemo.payment.services;

import com.hareesh.springstatemachine.springstatemachinedemo.payment.domain.Account;
import com.hareesh.springstatemachine.springstatemachinedemo.payment.domain.Payment;
import com.hareesh.springstatemachine.springstatemachinedemo.payment.domain.PaymentEvent;
import com.hareesh.springstatemachine.springstatemachinedemo.payment.domain.PaymentState;
import com.hareesh.springstatemachine.springstatemachinedemo.payment.exception.InsufficientFundsException;
import com.hareesh.springstatemachine.springstatemachinedemo.payment.exception.PaymentException;
import com.hareesh.springstatemachine.springstatemachinedemo.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

import static java.lang.String.format;

@RequiredArgsConstructor
@Service
public class PaymentServiceImpl implements PaymentService {

    static final Logger LOGGER = LoggerFactory.getLogger(PaymentServiceImpl.class);

    public static final String PAYMENT_ID_HEADER = "payment_id";

    private final PaymentRepository repository;

    private final StateMachineFactory<PaymentState, PaymentEvent> stateMachineFactory;

    private final PaymentStateChangeInterceptor paymentStateChangeInterceptor;

    @Override
    public Payment getPaymentById(Long paymentId) {
        return repository.getPaymentById(paymentId);
    }

    @Override
    public Payment createNewPayment(BigDecimal amount) throws InsufficientFundsException {
        Payment payment = new Payment();
        payment.setAmount(amount);
        payment.setState(PaymentState.INITIAL);
        Payment createdPayment = repository.save(payment);

        StateMachine<PaymentState, PaymentEvent> sm = build(payment.getId());
        preAuth(createdPayment.getId(), amount, sm);

        return payment;
    }

    @Override
    public Payment processPayment(Long paymentId) throws InsufficientFundsException, PaymentException {
        Payment payment = getPaymentById(paymentId);

        if (payment == null) {
            LOGGER.error("Payment with id {} not found", paymentId);
            throw new PaymentException("Payment with id " + paymentId + " not found");
        } else if (PaymentState.SUCCESS.equals(payment.getState()) ||
                PaymentState.DECLINED.equals(payment.getState())) {
            LOGGER.error("Payment with id {} already processed", paymentId);
            throw new PaymentException("Payment with id " + paymentId + " already processed");
        }

        StateMachine<PaymentState, PaymentEvent> sm = build(paymentId);
        if (payment.getAmount().compareTo(Account.accountBalance) <= 0) {
            Account.accountBalance = Account.accountBalance.subtract(payment.getAmount());
            sendEvent(paymentId, sm, PaymentEvent.SUBTRACT_MONEY);
        } else {
            sendEvent(paymentId, sm, PaymentEvent.DECLINE_PAYMENT);
            LOGGER.error("Payment with id {} has been declined. Not enough money", paymentId);
            throw new InsufficientFundsException("Not enough balance");
        }
        return payment;
    }

    @Override
    public List<Payment> getAllPayments() {
        return repository.findAll();
    }

    @Override
    public void preAuth(Long paymentId, BigDecimal amount, StateMachine<PaymentState, PaymentEvent> sm) throws InsufficientFundsException {
        if (amount.compareTo(Account.limitPerPayment) <= 0) {
            sendEvent(paymentId, sm, PaymentEvent.CREATE_PAYMENT);
        } else {
            sendEvent(paymentId, sm, PaymentEvent.DECLINE_PAYMENT);
            LOGGER.error("Payment with id {} has been declined. The amount {} is bigger than {}", paymentId, amount, Account.limitPerPayment);
            throw new InsufficientFundsException(format("The amount is bigger than %s, payment is declined.", Account.limitPerPayment));
        }
    }


    private void sendEvent(Long paymentId, StateMachine<PaymentState, PaymentEvent> sm, PaymentEvent event){
        Message<PaymentEvent> msg = MessageBuilder.withPayload(event)
                .setHeader(PAYMENT_ID_HEADER, paymentId)
                .build();

        sm.sendEvent(msg);
    }

    private void initializeExtendedState(StateMachine<PaymentState, PaymentEvent> sm, BigDecimal amount, Long paymentId) {
        sm.getExtendedState().getVariables().put("amount", amount);
        sm.getExtendedState().getVariables().put("paymentId", paymentId);
    }

    private StateMachine<PaymentState, PaymentEvent> build(Long paymentId) {
        StateMachine<PaymentState, PaymentEvent> stateMachine = stateMachineFactory.getStateMachine();

        Payment payment = repository.getOne(paymentId);

        stateMachine.stop();

        stateMachine.getStateMachineAccessor()
                .doWithAllRegions(sma -> {
                    sma.addStateMachineInterceptor(paymentStateChangeInterceptor);
                    sma.resetStateMachine(new DefaultStateMachineContext<>(payment.getState(), null, null, null));
                });

        initializeExtendedState(stateMachine, payment.getAmount(), paymentId);
        stateMachine.start();

        return stateMachine;
    }
}

package com.jpmc.midascore.component;

import com.jpmc.midascore.entity.TransactionRecord;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.repository.TransactionRecordRepository;
import com.jpmc.midascore.repository.UserRepository;
import com.jpmc.midascore.foundation.Transaction;
import com.jpmc.midascore.foundation.Balance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class DatabaseConduit {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConduit.class);

    private final UserRepository userRepository;
    private final TransactionRecordRepository transactionRecordRepository;
    private final RestTemplate restTemplate;
    private final String incentiveApiUrl;

    public DatabaseConduit(UserRepository userRepository,
                           TransactionRecordRepository transactionRecordRepository,
                           @Value("${incentive.api.url:}") String incentiveApiUrl) {
        this.userRepository = userRepository;
        this.transactionRecordRepository = transactionRecordRepository;
        this.restTemplate = new RestTemplate();
        this.incentiveApiUrl = incentiveApiUrl;
    }

    public void save(UserRecord userRecord) {
        userRepository.save(userRecord);
    }

    public void processTransaction(Transaction transaction) {
        UserRecord sender = userRepository.findById(transaction.getSenderId());
        UserRecord recipient = userRepository.findById(transaction.getRecipientId());

        if (sender != null && recipient != null && sender.getBalance() >= transaction.getAmount()) {
            sender.setBalance(sender.getBalance() - transaction.getAmount());

            float incentiveAmount = 0.0f;
            if (incentiveApiUrl != null && !incentiveApiUrl.isEmpty()) {
                try {
                    Balance incentive = restTemplate.postForObject(incentiveApiUrl, transaction, Balance.class);
                    if (incentive != null) {
                        incentiveAmount = incentive.getAmount();
                    }
                } catch (Exception e) {
                    logger.warn("Incentive API call failed: {}. Defaulting incentive to 0.0", e.getMessage());
                }
            }

            recipient.setBalance(recipient.getBalance() + transaction.getAmount() + incentiveAmount);

            userRepository.save(sender);
            userRepository.save(recipient);

            // Record the validated transaction (with incentive) to the database
            TransactionRecord record = new TransactionRecord(sender, recipient, transaction.getAmount(), incentiveAmount);
            transactionRecordRepository.save(record);
            logger.info("Saved TransactionRecord: senderId={}, recipientId={}, amount={}, incentive={}",
                    sender.getId(), recipient.getId(), transaction.getAmount(), incentiveAmount);
        } else {
            logger.debug("Transaction discarded (invalid sender/recipient or insufficient funds): {}", transaction);
        }
    }

    public Balance getBalance(long userId) {
        UserRecord user = userRepository.findById(userId);
        if (user != null) {
            return new Balance(user.getBalance());
        }
        return new Balance(0.0f);
    }
}

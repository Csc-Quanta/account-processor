package org.csc.account.core.actuator.utils;

import com.google.protobuf.ByteString;
import org.csc.account.api.IAccountHelper;
import org.csc.account.util.ByteUtil;
import org.csc.evmapi.gens.Act;
import org.csc.evmapi.gens.Tx;

import java.math.BigInteger;
import java.util.Map;
import java.util.function.BinaryOperator;

/**
 * ActuatorHelper
 * @author lance
 * @since 2019.3.13 16:10
 */
public final class ActuatorHelper {
    private ActuatorHelper(){}

    /**
     * 获取账户余额
     * @return  getBalance
     */
    public static BigInteger getBalance(ByteString address, Map<ByteString, Act.Account.Builder> accounts, IAccountHelper accountHelper){
        Act.Account.Builder account = getAccount(address, accounts, accountHelper);
        Act.AccountValue.Builder inputValue = account.getValue().toBuilder();
        return ByteUtil.bytesToBigInteger(inputValue.getBalance().toByteArray());
    }

    /**
     * 获取账户信息
     * @return  Act.Account
     */
    public static Act.Account.Builder getAccount(ByteString address, Map<ByteString, Act.Account.Builder> accounts, IAccountHelper accountHelper){
        Act.Account.Builder account = accounts.get(address);
        return account == null? accountHelper.getAccountOrCreate(address): account;
    }

    /**
     * 更新账户余额
     * @return Act.Account.Builder
     */
    public static Act.Account.Builder updateBalance(ByteString address, ByteString amount, BinaryOperator<byte[]> operator,
                                                     Map<ByteString, Act.Account.Builder> accounts, IAccountHelper accountHelper){
        return updateBalance(address, amount, operator,0, accounts, accountHelper);
    }

    /**
     * 更新账户信息
     * @return Act.Account.Builder
     */
    public static Act.Account.Builder updateBalance(ByteString address, ByteString amount, BinaryOperator<byte[]> operator, int curNonce,
                                                    Map<ByteString, Act.Account.Builder> accounts, IAccountHelper accountHelper){
        Act.Account.Builder account = getAccount(address, accounts, accountHelper);
        Act.AccountValue.Builder value = account.getValue().toBuilder();
        value.setBalance(ByteString.copyFrom(operator.apply(value.getBalance().toByteArray(), amount.toByteArray())));
        if(curNonce != 0){
            value.setNonce(curNonce+1);
        }
        account.setValue(value);
        return account;
    }

    /**
     * 获取Output总金额
     * @return  BigInteger
     */
    public static BigInteger getOutputAmount(Tx.Transaction tx){
        return tx.getBody().getOutputsList().stream()
                .map(Tx.TransactionOutput::getAmount)
                .map(ByteString::toByteArray)
                .map(ByteUtil::bytesToBigInteger)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }
}

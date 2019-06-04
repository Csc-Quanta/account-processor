package org.csc.account.core.actuator.token;

import com.google.protobuf.ByteString;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.core.actuator.AbstractTransactionActuator;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.util.ByteUtil;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Act.AccountValue;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx.Transaction;
import org.csc.evmapi.gens.Tx.TransactionInput;
import org.csc.evmapi.gens.Tx.TransactionOutput;

import java.math.BigInteger;
import java.util.Map;

/**
 * 添加分片验证
 * （1->*）
 *
 * @author lance
 * @since 2019.1.8 20:46
 */
public class ActuatorTokenTransaction extends AbstractTransactionActuator {

    public ActuatorTokenTransaction(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper,
                                    BlockEntity oBlock, EncAPI encApi, IStateTrie oStateTrie) {
        super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
    }

    /**
     * 预处理
     *
     * @param tx          Tx
     * @param accounts    Account[Input, Output]
     * @throws BlockException BlockException
     */
    @Override
    public void onPrepareExecute(Transaction tx, Map<ByteString, Account.Builder> accounts)
            throws BlockException {

        if (tx.getBody().getInput() == null) {
            throw new TransactionParameterInvalidException("parameter invalid, inputs must be only one");
        }

        if (tx.getBody().getOutputsCount() == 0) {
            throw new TransactionParameterInvalidException("parameter invalid, outputs must not be null");
        }

        String token = "";
        BigInteger inputsTotal = BigInteger.ZERO;
        TransactionInput input = tx.getBody().getInput();
        if ("".equals(token)) {
            token = input.getToken().toStringUtf8();
        }

        if (!token.equals(input.getToken().toStringUtf8())) {
            throw new TransactionParameterInvalidException(String.format("parameter invalid, not allow multi token %s %s", token, input.getToken()));
        }

        //转出token累计
        inputsTotal = inputsTotal.add(ByteUtil.bytesToBigInteger(input.getAmount().toByteArray()));

        //不在同一个分片上面, 不需要执行
        /*if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())){
            continue;
        }*/

        if (input.getToken().isEmpty()) {
            throw new TransactionParameterInvalidException("parameter invalid, token must not be empty");
        }

        // 取发送方账户
        ByteString address = input.getAddress();
        Account.Builder sender = accounts.get(address);
        AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();

        BigInteger tokenBalance = oAccountHelper.getTokenBalance(sender, input.getToken());
        if (tokenBalance.compareTo(BigInteger.ZERO) < 0) {
            throw new TransactionParameterInvalidException(String.format("parameter invalid, sender balance %s less than 0", tokenBalance));
        }
        //减去转账后剩余token是否大于0
        BigInteger newBalance = tokenBalance.subtract(ByteUtil.bytesToBigInteger(input.getAmount().toByteArray()));
        if (newBalance.compareTo(BigInteger.ZERO) < 0) {
            throw new TransactionParameterInvalidException(String.format("parameter invalid, sender balance %s less than %s", tokenBalance,
                    ByteUtil.bytesToBigInteger(input.getAmount().toByteArray())));
        }

        //转出token累计
        if (ByteUtil.bytesToBigInteger(input.getAmount().toByteArray()).compareTo(BigInteger.ZERO) < 0) {
            throw new TransactionParameterInvalidException(String.format("parameter invalid, transaction value %s less than 0",
                    ByteUtil.bytesToBigInteger(input.getAmount().toByteArray())));
        }

        int nonce = senderAccountValue.getNonce();
        if (nonce > input.getNonce()) {
            throw new TransactionParameterInvalidException(String.format("parameter invalid, sender nonce %s is not equal with transaction nonce %s", nonce,
                    input.getNonce()));
        }

        //接收token验证
        BigInteger outputsTotal = BigInteger.ZERO;
        for (TransactionOutput output : tx.getBody().getOutputsList()) {
            if (ByteUtil.bytesToBigInteger(output.getAmount().toByteArray()).compareTo(BigInteger.ZERO) < 0) {
                throw new TransactionParameterInvalidException(String.format("parameter invalid, receive balance %s less than 0",
                        ByteUtil.bytesToBigInteger(output.getAmount().toByteArray())));
            }
            outputsTotal = ByteUtil.bytesAdd(outputsTotal, output.getAmount().toByteArray());
        }

        if (inputsTotal.compareTo(outputsTotal) != 0) {
            throw new TransactionParameterInvalidException(String.format("parameter invalid, transaction value %s not equal with %s", inputsTotal, outputsTotal));
        }
    }

    /**
     * 执行交易
     *
     * @param tx          交易
     * @param accounts    Account[Input, Output]
     * @return Empty
     * @throws BlockException BlockException
     */
    @Override
    public ByteString onExecute(Transaction tx, Map<ByteString, Account.Builder> accounts) throws BlockException {
        TransactionInput input = tx.getBody().getInput();
        ByteString token = input.getToken();

        //不在同一个分片上面, 不需要执行
        /*if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())){
            continue;
        }*/

        ByteString address = input.getAddress();
        Account.Builder sender = accounts.get(address);

        BigInteger amount = ByteUtil.bytesToBigInteger(input.getAmount().toByteArray());
        oAccountHelper.subTokenBalance(sender, input.getToken(), amount);

        AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();
        senderAccountValue.setNonce(senderAccountValue.getNonce() + 1);
        sender.setValue(senderAccountValue);
        accounts.put(sender.getAddress(), sender);

        //接收token账户添加token
        for (TransactionOutput output : tx.getBody().getOutputsList()) {
            //不在同一个分片上面, 不需要执行
            if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(output.getSliceId())){
                continue;
            }

            ByteString outAddress = output.getAddress();
            Account.Builder receiver = accounts.get(outAddress);
            if (receiver == null) {
                receiver = oAccountHelper.createAccount(outAddress);
            }

            BigInteger outAmount = ByteUtil.bytesToBigInteger(output.getAmount().toByteArray());
            oAccountHelper.addTokenBalance(receiver, token, outAmount);

            AccountValue.Builder receiverAccountValue = receiver.getValue().toBuilder();
            receiver.setValue(receiverAccountValue);
            accounts.put(receiver.getAddress(), receiver);

        }

        return ByteString.EMPTY;
    }
}

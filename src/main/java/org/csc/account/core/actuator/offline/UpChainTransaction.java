package org.csc.account.core.actuator.offline;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.core.actuator.AbstractTransactionActuator;
import org.csc.account.core.actuator.utils.ActuatorHelper;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.processor.BlockChainConfig;
import org.csc.account.util.ByteUtil;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act;
import org.csc.evmapi.gens.Block;
import org.csc.evmapi.gens.Tx;

import java.math.BigInteger;
import java.util.Map;
import java.util.function.BinaryOperator;

/**
 * 针对离线交易记录上链
 * Offline.Wallet ----> MainChain(1->*)
 * @author lance
 * @since 2019.3.12 14:46
 */
@Slf4j
public class UpChainTransaction extends AbstractTransactionActuator {

    public UpChainTransaction(IAccountHelper oIAccountHelper, ITransactionHelper oTransactionHelper, Block.BlockEntity currentBlock, EncAPI encApi, IStateTrie oIStateTrie) {
        super(oIAccountHelper, oTransactionHelper, currentBlock, encApi, oIStateTrie);
    }

    @Override
    public void onPrepareExecute(Tx.Transaction tx, Map<ByteString, Act.Account.Builder> accounts) throws BlockException {
        Tx.TransactionInput input = tx.getBody().getInput();
        if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())) {
            return;
        }

        validUpTransaction(tx, accounts);
    }

    @Override
    public ByteString onExecute(Tx.Transaction tx, Map<ByteString, Act.Account.Builder> accounts) throws BlockException {
        validUpTransaction(tx, accounts);

        //处理output账户
        tx.getBody().getOutputsList().forEach(t -> {
            Act.Account.Builder account = ActuatorHelper.updateBalance(t.getAddress(), t.getAmount(), ByteUtil::bytesAddToBytes, accounts, oAccountHelper);
            accounts.put(t.getAddress(), account);
        });

        BigInteger total = ActuatorHelper.getOutputAmount(tx);
        ByteString amount = ByteString.copyFrom(ByteUtil.bigIntegerToBytes(total));
        ByteString address = ByteString.copyFrom(encApi.hexDec(BlockChainConfig.OFFLINE_ADDRESS));
        Act.Account.Builder account = ActuatorHelper.updateBalance(address, amount, ByteUtil::bytesSubToBytes, accounts, oAccountHelper);
        accounts.put(address, account);
        return ByteString.EMPTY;
    }

    /**
     * 验证交易
     * @param tx       Transaction
     * @param accounts accounts
     */
    private void validUpTransaction(Tx.Transaction tx, Map<ByteString, Act.Account.Builder> accounts){
        Tx.TransactionInput input =  tx.getBody().getInput();
        if (input != null) {
            throw new TransactionParameterInvalidException("Inputs must be null");
        }

        //交易只有Output, 无Input
        if(tx.getBody().getOutputsCount() == 0){
            throw new TransactionParameterInvalidException("Outputs must be exist");
        }

        //交易必须签名
        if (tx.getBody().getSignatures().isEmpty()) {
            throw new TransactionParameterInvalidException("Signatures must be exist");
        }

        //验证账户剩余金额balance是否大于amount
        ByteString inputAddress = ByteString.copyFrom(encApi.hexDec(BlockChainConfig.OFFLINE_ADDRESS));
        BigInteger balance = ActuatorHelper.getBalance(inputAddress, accounts, oAccountHelper);
        if(balance.compareTo(BigInteger.ZERO)<=0){
            throw new TransactionParameterInvalidException("Amount must be greater than 0");
        }

        //验证上送的交易总金额是否小于默认地址账户余额
        BigInteger total = ActuatorHelper.getOutputAmount(tx);
        if(balance.compareTo(total) < 0){
            throw new TransactionParameterInvalidException(format("Balance: %s, Account: %s", balance.toString(), total.toString()));
        }
    }
}

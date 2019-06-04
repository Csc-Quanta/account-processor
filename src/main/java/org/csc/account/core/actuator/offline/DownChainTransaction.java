package org.csc.account.core.actuator.offline;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 针对离线交易下链到离线钱包,
 * 主链个人账户扣除金额, 转到某个指定账户上面, 并对交易信息进行签名
 * MainChain ----> Offline.Wallet
 * @author lance
 * @since 2019.3.12 14:46
 */
@Slf4j
public class DownChainTransaction extends AbstractTransactionActuator {

    public DownChainTransaction(IAccountHelper oIAccountHelper, ITransactionHelper oTransactionHelper, Block.BlockEntity currentBlock, EncAPI encApi, IStateTrie oIStateTrie) {
        super(oIAccountHelper, oTransactionHelper, currentBlock, encApi, oIStateTrie);
    }

    @Override
    public void onPrepareExecute(Tx.Transaction tx, Map<ByteString, Act.Account.Builder> accounts) throws BlockException {
        Tx.TransactionInput input = tx.getBody().getInput();
        if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())) {
            return;
        }

        validDownTransaction(tx, accounts);
    }

    @Override
    public ByteString onExecute(Tx.Transaction tx, Map<ByteString, Act.Account.Builder> accounts) throws BlockException {
        Tx.TransactionInput input = tx.getBody().getInput();
        if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())) {
            return ByteString.EMPTY;
        }
        //验证
        validDownTransaction(tx, accounts);

        //处理主链账户(扣除申请离线金额)
        Act.Account.Builder account = ActuatorHelper.updateBalance(input.getAddress(), input.getAmount(),
                ByteUtil::bytesSubToBytes, input.getNonce(),accounts,oAccountHelper);

        //处理离线钱包账户(离线固定地址需要添加该笔金额)
        ByteString offAddress = ByteString.copyFrom(encApi.hexDec(BlockChainConfig.OFFLINE_ADDRESS));
        Act.Account.Builder offAccount = ActuatorHelper.updateBalance(offAddress, input.getAmount(),
                ByteUtil::bytesAddToBytes, accounts, oAccountHelper);

        //待处理手续费(主链账户-fee, 手续费账户+fee)
        //Here your code.

        //处理完成两个账户后, 重新放该两个账户
        accounts.put(input.getAddress(), account);
        accounts.put(offAddress, offAccount);
        return ByteString.EMPTY;
    }

    /**
     * 验证交易
     * @param tx       Transaction
     * @param accounts accounts
     */
    private void validDownTransaction(Tx.Transaction tx, Map<ByteString, Act.Account.Builder> accounts){
        Tx.TransactionInput input =  tx.getBody().getInput();
        if (input == null) {
            throw new TransactionParameterInvalidException("Inputs must be only one");
        }

        //交易只有input, 无Output
        if(tx.getBody().getOutputsCount() != 0){
            throw new TransactionParameterInvalidException("Outputs must be null");
        }

        //交易必须签名
        if (tx.getBody().getSignatures().isEmpty()) {
            throw new TransactionParameterInvalidException("Signatures must be exist");
        }

        //验证请求转出金额
        BigInteger amount = ByteUtil.bytesToBigInteger(input.getAmount().toByteArray());
        if(amount.compareTo(BigInteger.ZERO)<=0){
            throw new TransactionParameterInvalidException("Amount must be greater than 0");
        }

        //验证账户剩余金额balance是否大于amount
        Act.Account.Builder account = accounts.get(input.getAddress());
        Act.AccountValue.Builder value = account.getValue().toBuilder();
        BigInteger balance = ByteUtil.bytesToBigInteger(value.getBalance().toByteArray());
        if(balance.compareTo(amount) < 0){
            throw new TransactionParameterInvalidException(format("Balance: %s, Account: %s", balance.toString(), account.toString()));
        }

        //验证账户余额是否大于(转出金额 +手续费)
        if((balance.compareTo(amount.add(BlockChainConfig.OFFLINE_DOWN_FEE))) < 0){
            throw new TransactionParameterInvalidException(
                    format("Balance: %s, Account: %s, Fee: %s", balance.toString(), account.toString(), BlockChainConfig.OFFLINE_DOWN_FEE.toString()));
        }

        //验证nonce
        if (value.getNonce() > input.getNonce()) {
            throw new TransactionParameterInvalidException(format("Sender nonce %s is not equal with transaction nonce %s", value.getNonce(), input.getNonce()));
        }
    }
}
